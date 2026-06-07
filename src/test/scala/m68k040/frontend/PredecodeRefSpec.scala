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
  test("EOR.W D0,(A0) mem-dest (opmode5) -> complex (RMW deferred)") { assert(classify(0xB150) == cp(false,0)) }
  test("EOR Dn,Dm register dest (opmode 4/5/6, mode0) -> simple len1") {
    assert(classify(0xB382) == cp(true,1))   // EOR.L D1,D2
    assert(classify(0xB302) == cp(true,1))   // EOR.B D1,D2
    assert(classify(0xB342) == cp(true,1))   // EOR.W D1,D2
    assert(classify(0xB389) == cp(false,0))  // EOR.L D1,A1 (An-direct = CMPM) -> complex
  }
  test("indexed (d8,A0,Xn) source -> complex") { assert(classify(0xD0B0) == cp(false,0)) }
  test("deferred ops -> complex") {
    assert(classify(0x5240) == cp(false,0))  // ADDQ.W #1,D0
    assert(classify(0x51C8) == cp(false,0))  // DBRA D0
    assert(classify(0xE148) == cp(false,0))  // LSL.W #8,D0
    assert(classify(0x41D0) == cp(false,0))  // LEA (A0),A0
  }
  test("line-0 immediates ADDI/SUBI/ANDI/ORI/EORI/CMPI -> Dn (in scope)") {
    assert(classify(0x0000) == cp(true,2))   // ORI.B  #imm,D0 (.B = opword + 1 imm word)
    assert(classify(0x0240) == cp(true,2))   // ANDI.W #imm,D0 (.W = opword + 1 imm word)
    assert(classify(0x0480) == cp(true,3))   // SUBI.L #imm,D0 (.L = opword + 2 imm words)
    assert(classify(0x0680) == cp(true,3))   // ADDI.L #imm,D0
    assert(classify(0x0A40) == cp(true,2))   // EORI.W #imm,D0
    assert(classify(0x0C80) == cp(true,3))   // CMPI.L #imm,D0
  }
  test("line-0 ANDI/ORI/EORI #imm,CCR (...00 111100 byte) -> simple len2; SR/CMPI-to-ccr complex") {
    assert(classify(0x023C) == cp(true,2))   // ANDI #imm,CCR
    assert(classify(0x003C) == cp(true,2))   // ORI  #imm,CCR
    assert(classify(0x0A3C) == cp(true,2))   // EORI #imm,CCR
    assert(classify(0x027C) == cp(false,0))  // ANDI #imm,SR (word, privileged) -> deferred
    assert(classify(0x0C3C) == cp(false,0))  // CMPI #imm,<#imm> (no CMPI-to-CCR) -> illegal
  }
  test("line-0 memory-dest immediate (deferred RMW) + illegal size -> complex") {
    assert(classify(0x0010) == cp(false,0))  // ORI.B #imm,(A0) — RMW deferred
    assert(classify(0x00C0) == cp(false,0))  // ss=11 illegal size
    assert(classify(0x0840) == cp(false,0))  // opmode 4 (BTST-imm / bit ops) — out of scope
  }
  test("DIVU.W/DIVS.W (class 8 opmode 3/7) + MULU.W/MULS.W (class C) -> simple; An-direct MUL EA complex") {
    assert(classify(0x80C1) == cp(true,1))   // DIVU.W D1,D0 (reg divisor, 1 word)
    assert(classify(0x81C1) == cp(true,1))   // DIVS.W D1,D0
    assert(classify(0x80FC) == cp(true,2))   // DIVU.W #imm,D0 (imm divisor -> +1 ext word)
    assert(classify(0xC0C1) == cp(true,1))   // MULU.W D1,D0 (reg multiplier, 1 word)
    assert(classify(0xC1C1) == cp(true,1))   // MULS.W D1,D0
    assert(classify(0xC0FC) == cp(true,2))   // MULU.W #imm,D0 (imm -> +1 ext word)
    assert(classify(0xC0C9) == cp(false,0))  // MULU.W A1,D0 — An-direct is NOT a legal MUL EA
  }
  test("CHK.W/CHK.L (class 4 bit8=1 bit6=0) -> simple") {
    assert(classify(0x4181) == cp(true,1))   // CHK.W D1,D0 (reg bound)
    assert(classify(0x4101) == cp(true,1))   // CHK.L D1,D0
    assert(classify(0x41BC) == cp(true,2))   // CHK.W #imm,D0 (imm -> +1 word)
  }
  test("DIVU.L/DIVS.L (0100110001 mmmrrr) / MULU.L/MULS.L (0100110000) -> simple len 2 (reg/imm)") {
    assert(classify(0x4C41) == cp(true,2))   // DIVU.L D1,D0 (opword + ext word)
    assert(classify(0x4C7C) == cp(true,4))   // DIVU.L #imm,D0 (opword + ext + 2 imm words)
    assert(classify(0x4C01) == cp(true,2))   // MULU.L D1,D0 (opword + ext word; RTL frames it)
    assert(classify(0x4C3C) == cp(true,4))   // MULU.L #imm,D0 (opword + ext + 2 imm words)
  }
  test("ADDA/SUBA (class 9/D opmode 3/7) stay simple len1") {
    assert(classify(0xD1C9) == cp(true,1))   // ADDA.L A1,A0
    assert(classify(0x90C9) == cp(true,1))   // SUBA.W A1,A0
  }
}
