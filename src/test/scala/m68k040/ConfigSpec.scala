package m68k040

import org.scalatest.funsuite.AnyFunSuite

class ConfigSpec extends AnyFunSuite {
  test("default params expose the Appendix A baselines") {
    val p = M68kParams()
    assert(p.robDepth == 64)
    assert(p.physInt == 50)   // 48 + headroom for the 2 EA-cracking temp arch regs
    assert(p.physNzvc == 16)
    assert(p.physX == 16)
    assert(p.decodeWidth == 2)
    assert(p.retireWidth == 2)
    assert(p.l1dKb == 16 && p.l1iKb == 16)
  }

  test("derived widths are consistent") {
    val p = M68kParams()
    assert(p.robIdWidth == 6)        // log2Up(64)
    assert(p.physIntIdWidth == 6)    // log2Up(48) == 6
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
