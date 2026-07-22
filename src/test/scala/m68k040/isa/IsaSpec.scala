package m68k040.isa

import org.scalatest.funsuite.AnyFunSuite

class IsaSpec extends AnyFunSuite {
  test("20 architectural integer regs: D0-D7, A0-A7 + 4 EA-cracking temps T0/T1/T2/T3") {
    assert(Isa.ARCH_INT_REGS == 20)
  }
  test("CCR bit positions match m68k (X N Z V C)") {
    assert(Isa.CCR_C == 0 && Isa.CCR_V == 1 && Isa.CCR_Z == 2 && Isa.CCR_N == 3 && Isa.CCR_X == 4)
  }
  test("NZVC mask covers bits 3..0 and X mask is bit 4") {
    assert(Isa.MASK_NZVC == 0xF)
    assert(Isa.MASK_X == 0x10)
  }
}
