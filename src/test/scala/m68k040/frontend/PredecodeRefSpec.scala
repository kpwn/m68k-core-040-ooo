package m68k040.frontend

import org.scalatest.funsuite.AnyFunSuite

class PredecodeRefSpec extends AnyFunSuite {
  import PredecodeRef.{classify, CP}
  def cp(simple: Boolean, len: Int) = CP(simple, len)

  test("MOVEQ -> simple len1") { assert(classify(0x7000) == cp(true,1)); assert(classify(0x7E05) == cp(true,1)) }
  test("0111 with bit8=1 not MOVEQ -> complex") { assert(classify(0x7100) == cp(false,0)) }
  test("MOVE.W D0,D1 reg->reg -> simple len1") { assert(classify(0x3200) == cp(true,1)) }
  test("MOVE.L #imm32,D0 -> simple len3") { assert(classify(0x203C) == cp(true,3)) }
  test("MOVE.L (d16,A0),D1 -> simple len2") { assert(classify(0x2228) == cp(true,2)) }
  test("MOVE.L (A0),(A1) mem->mem -> complex") { assert(classify(0x2290) == cp(false,0)) }
  test("MOVE.L abs.L,abs.L mem->mem -> complex") { assert(classify(0x23F9) == cp(false,0)) }
  test("BRA.s -> simple len1") { assert(classify(0x6002) == cp(true,1)) }
  test("BRA.w disp==0 -> simple len2") { assert(classify(0x6000) == cp(true,2)) }
  test("BRA.l disp==0xFF -> simple len3") { assert(classify(0x60FF) == cp(true,3)) }
  test("ADD.L (A0),D0 EA->Dn -> simple len1") { assert(classify(0xD090) == cp(true,1)) }
  test("ADD.L D0,(A0) RMW -> complex") { assert(classify(0xD190) == cp(false,0)) }
  test("ADDA.L A1,A0 opmode111 -> simple len1") { assert(classify(0xD1C9) == cp(true,1)) }
  test("CMP.W (d16,A0),D0 -> simple len2") { assert(classify(0xB068) == cp(true,2)) }
  test("EOR.W D0,(A0) class B opmode101 -> complex") { assert(classify(0xB150) == cp(false,0)) }
  test("indexed (d8,A0,Xn) source -> complex") { assert(classify(0xD0B0) == cp(false,0)) }
  test("deferred ops -> complex") {
    assert(classify(0x5240) == cp(false,0))  // ADDQ.W #1,D0
    assert(classify(0x51C8) == cp(false,0))  // DBRA D0
    assert(classify(0xE148) == cp(false,0))  // LSL.W #8,D0
    assert(classify(0x0240) == cp(false,0))  // ANDI.W
    assert(classify(0x41D0) == cp(false,0))  // LEA (A0),A0
  }
}
