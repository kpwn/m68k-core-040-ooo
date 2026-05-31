package m68k040.types

import m68k040.M68kParams
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class RobEntrySpec extends AnyFunSuite {
  test("RobEntry and PredecodeMeta elaborate with spec fields") {
    SpinalConfig().generateVerilog(new Component {
      val p    = M68kParams()
      val rob  = RobEntry(p)
      val meta = PredecodeMeta()
      rob.assignDontCare()
      meta.assignDontCare()
      val probe = out(Bool())
      probe := rob.valid ^ meta.simple

      assert(rob.outstandingUops.getWidth == 3)   // up to 4 µops -> 3-bit counter
      assert(rob.ccrWriteMask.getWidth == 5)       // X,N,Z,V,C
      assert(meta.ccrReadMask.getWidth == 5)
    })
  }
}
