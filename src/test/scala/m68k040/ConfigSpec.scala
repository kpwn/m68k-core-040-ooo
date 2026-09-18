package m68k040

import org.scalatest.funsuite.AnyFunSuite

class ConfigSpec extends AnyFunSuite {
  test("default params expose the configured sizing baselines") {
    val p = M68kParams()
    assert(p.robDepth == Global.ROB_DEPTH_DEFAULT)
    assert(p.physInt == Global.PHYS_INT_REGS_DEFAULT)
    assert(p.physNzvc == 16)
    assert(p.physX == 16)
    assert(p.decodeWidth == 2)
    assert(p.retireWidth == 2)
    assert(p.l1iKb == 16)
  }

  test("derived widths are consistent") {
    val p = M68kParams()
    assert(p.robIdWidth == spinal.core.log2Up(p.robDepth))
    assert(p.physIntIdWidth == spinal.core.log2Up(p.physInt))
  }

  test("derived widths track non-default sizing") {
    val p = M68kParams(robDepth = 32, storeQDepth = 16, loadQDepth = 4)
    assert(p.robIdWidth == 5)    // log2Up(32)
    assert(p.sqPtrWidth == 4)    // log2Up(16)
    assert(p.lqPtrWidth == 2)    // log2Up(4)
    val d = M68kParams()
    assert(d.physNzvcIdWidth == 4 && d.physXIdWidth == 4)  // log2Up(16)
  }
}
