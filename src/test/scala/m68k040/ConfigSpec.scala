package m68k040

import org.scalatest.funsuite.AnyFunSuite

class ConfigSpec extends AnyFunSuite {
  test("default params expose the Appendix A baselines") {
    val p = M68kParams()
    assert(p.robDepth == 64)
    assert(p.physInt == 48)
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
}
