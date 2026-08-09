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
  // mem-to-mem MOVE is now in scope (cracked into [load][store] + folded An auto-updates).
  test("MOVE.L (A0),(A1) mem->mem -> simple len1") { assert(classify(0x2290) == cp(true,1)) }
  test("MOVE.L abs.L,abs.L mem->mem -> simple len5 (2+2 ext)") { assert(classify(0x23F9) == cp(true,5)) }
  test("BRA.s -> simple len1") { assert(classify(0x6002) == cp(true,1)) }
  test("BRA.w disp==0 -> simple len2") { assert(classify(0x6000) == cp(true,2)) }
  test("BRA.l disp==0xFF -> simple len3") { assert(classify(0x60FF) == cp(true,3)) }
  test("ADD.L (A0),D0 EA->Dn -> simple len1") { assert(classify(0xD090) == cp(true,1)) }
  test("ADD.L D0,(A0) RMW mem-dest -> simple len1 (An)") { assert(classify(0xD190) == cp(true,1)) }
  test("ADD.W D0,(d16,A0) RMW -> simple len2; (xxx).L -> len3; (A0)+ -> simple len1") {
    assert(classify(0xD168) == cp(true,2))   // ADD.W D0,(d16,A0) -> opword + disp16
    assert(classify(0xD1B9) == cp(true,3))   // ADD.L D0,(xxx).L -> opword + abs32
    assert(classify(0xD198) == cp(true,1))   // ADD.L D0,(A0)+ -> RMW postinc (0 ext, An folded)
  }
  test("ADDA.L A1,A0 opmode111 -> simple len1") { assert(classify(0xD1C9) == cp(true,1)) }
  test("CMP.W (d16,A0),D0 -> simple len2") { assert(classify(0xB068) == cp(true,2)) }
  test("EOR.W D0,(A0) mem-dest (opmode5) -> simple len1 (RMW)") { assert(classify(0xB150) == cp(true,1)) }
  test("line-B register EOR and CMPM are distinct single-word instructions") {
    assert(classify(0xB382) == cp(true,1))   // EOR.L D1,D2
    assert(classify(0xB302) == cp(true,1))   // EOR.B D1,D2
    assert(classify(0xB342) == cp(true,1))   // EOR.W D1,D2
    // Mode 001 is not an address-register EOR destination. In the line-B opmode-4/5/6
    // band it is the legal, fixed one-word CMPM encoding: 0xB389 = CMPM.L (A1)+,(A1)+.
    // This assertion intentionally guards the stale expectation that used to make the
    // mandatory gate report an "EOR Dn,Dm" mismatch even though the failing word was CMPM.
    assert(classify(0xB389) == cp(true,1))
  }
  // Brief-format indexed (d8,An,Xn)/(d8,PC,Xn) are now IN SCOPE -> simple, +1 ext word
  // (was deferred/complex). Predecode frames the brief case (1 ext word); the assembler
  // illegalises a full-format (bit8=1) EA, where a mis-framed length is harmless.
  test("indexed (d8,A0,Xn) source -> simple len2 (brief ext word)") {
    assert(classify(0xD0B0) == cp(true,2))   // ADD.B (d8,A0,Xn),D0  src mode 6
    assert(classify(0x2430) == cp(true,2))   // MOVE.L (d8,A0,Xn),D2 src mode 6
    assert(classify(0x243B) == cp(true,2))   // MOVE.L (d8,PC,Xn),D2 src mode 7-3
  }
  test("indexed (d8,An,Xn) destination -> simple len2 (MOVE + RMW)") {
    assert(classify(0x2183) == cp(true,2))   // MOVE.L D3,(d8,A0,Xn) dst mode 6
    assert(classify(0xD3B0) == cp(true,2))   // ADD.L D1,(d8,A0,Xn)  RMW dst mode 6
  }
  test("JMP and JSR control-EA partition includes both brief-indexed modes") {
    for {
      base <- Seq(0x4E80, 0x4EC0)            // JSR, JMP
      mode <- 0 until 8
      reg  <- 0 until 8
    } {
      val op = base | (mode << 3) | reg
      val expected = mode match {
        case 2 => cp(true, 1)                 // (An)
        case 5 => cp(true, 2)                 // (d16,An)
        case 6 => cp(true, 2)                 // (d8,An,Xn), brief indexed
        case 7 => reg match {
          case 0 => cp(true, 2)               // (xxx).W
          case 1 => cp(true, 3)               // (xxx).L
          case 2 => cp(true, 2)               // (d16,PC)
          case 3 => cp(true, 2)               // (d8,PC,Xn), brief indexed
          case _ => cp(false, 0)
        }
        case _ => cp(false, 0)                // Dn/An/(An)+/-(An) are not control EAs
      }
      assert(classify(op) == expected,
        f"${if (base == 0x4E80) "JSR" else "JMP"} mode=$mode reg=$reg op=0x$op%04x")
    }
  }
  test("ADDQ #n,(An) mem-dest -> simple (RMW now in scope)") {
    assert(classify(0x5290) == cp(true,1))   // ADDQ.L #1,(A0) -> (An) mem-dest RMW
    assert(classify(0x5268) == cp(true,2))   // ADDQ.W #1,(d16,A0) -> opword + disp16
    assert(classify(0x5298) == cp(true,1))   // ADDQ.L #1,(A0)+ -> RMW postinc (0 ext, An folded)
  }
  test("deferred ops -> complex") {
    // TRAPcc is now IN SCOPE: 0x50FA = TRAPT.W (cond=T, ttt=2, #data16) -> simple len2
    assert(classify(0x50FA) == cp(true,2))   // TRAPcc.W (cond=T, ttt=2): simple len2
    assert(classify(0xE0D0) == cp(false,0))  // ASR.W (A0) (line-E memory single-bit, ss=11) -> deferred
  }
  test("TRAPcc forms: ttt=4 (1w) / ttt=2 (+w16) / ttt=3 (+l32) / ttt=0 (illegal)") {
    assert(classify(0x51FC) == cp(true,1))   // TRAPF  (cc=F, ttt=4): no-operand, 1 word
    assert(classify(0x50FC) == cp(true,1))   // TRAPT  (cc=T, ttt=4): no-operand, 1 word
    assert(classify(0x57FA) == cp(true,2))   // TRAPEQ (cc=EQ, ttt=2): #data16, 2 words
    assert(classify(0x59FB) == cp(true,3))   // TRAPVS (cc=VS, ttt=3): #data32, 3 words
    assert(classify(0x50F8) == cp(false,0))  // TRAPcc ttt=0 (invalid) -> COMPLEX (illegal)
    assert(classify(0x50F9) == cp(false,0))  // TRAPcc ttt=1 (invalid) -> COMPLEX (illegal)
    assert(classify(0x50FD) == cp(false,0))  // TRAPcc ttt=5 (invalid) -> COMPLEX (illegal)
  }
  // LEA (A0),A0 (0x41D0) is now IN SCOPE (Track C) -> simple, len 1 (mode 2, no ext word).
  test("LEA -> simple (Track C, in scope)") {
    assert(classify(0x41D0) == cp(true,1))   // LEA (A0),A0
  }
  // Line-5 ADDQ/SUBQ (Dn/An dest) + Scc (Dn) + DBcc (now in scope).
  test("line-5 ADDQ/SUBQ + Scc + DBcc -> simple (in scope)") {
    assert(classify(0x5240) == cp(true,1))   // ADDQ.W #1,D0
    assert(classify(0x5248) == cp(true,1))   // ADDQ.W #1,A0 (An dest)
    assert(classify(0x5701) == cp(true,1))   // SUBQ.B #3,D1
    assert(classify(0x57C2) == cp(true,1))   // SEQ D2 (Scc Dn)
    assert(classify(0x51C8) == cp(true,2))   // DBRA D0 + disp16 -> len2
    assert(classify(0x57CE) == cp(true,2))   // DBEQ D6 + disp16 -> len2
  }
  test("line-E register-form shifts/rotates -> simple len1 (in scope)") {
    assert(classify(0xE148) == cp(true,1))   // LSL.W #8,D0
    assert(classify(0xE380) == cp(true,1))   // ASL.L #1,D0
    assert(classify(0xE32A) == cp(true,1))   // LSL.B Dc,D2 (register count)
    assert(classify(0xE493) == cp(true,1))   // ROXR.L #2,D3
    assert(classify(0xE0D8) == cp(false,0))  // ss=11 memory form -> complex
  }
  // Bit-field MEMORY forms (op[11]=1, ss=3, mode>=2): simple, len = opword + bf-ext + EA ext.
  test("line-E bit-field MEMORY forms -> simple len 2 + EA ext") {
    assert(classify(0xE8D0) == cp(true,2))   // BFTST  (A0){...}    mode 2 -> 2 + 0
    assert(classify(0xE9D0) == cp(true,2))   // BFEXTU (A0){...}    mode 2 -> 2 + 0
    assert(classify(0xEBD0) == cp(true,2))   // BFEXTS (A0){...}    mode 2 -> 2 + 0
    assert(classify(0xEDD0) == cp(true,2))   // BFFFO  (A0){...}    mode 2 -> 2 + 0
    assert(classify(0xE8E8) == cp(true,3))   // BFTST  (d16,A0){..}  mode 5 -> 2 + 1
    assert(classify(0xE8F9) == cp(true,4))   // BFTST  (xxx).L{..}   mode 7-1 -> 2 + 2
    assert(classify(0xEAD0) == cp(true,2))   // BFCHG  (A0){...} RMW mode 2 -> 2 + 0
    assert(classify(0xECD0) == cp(true,2))   // BFSET  (A0){...} RMW mode 2 -> 2 + 0
    assert(classify(0xEFD0) == cp(true,2))   // BFINS  (A0){...} RMW mode 2 -> 2 + 0
    assert(classify(0xE8C0) == cp(true,2))   // BFTST Dn (mode 0) -> register form (bitfieldReg) len2
    assert(classify(0xE8F0) == cp(true,3))   // BFTST (d8,A0,Xn){..} mode 6 brief-indexed -> 2 + 1
    assert(classify(0xE8FC) == cp(false,0))  // BFTST #imm{..} (mode 7-4) -> not allowed (eaExt allowImm=false) -> complex
  }
  test("line-0 immediates ADDI/SUBI/ANDI/ORI/EORI/CMPI -> Dn (in scope)") {
    assert(classify(0x0000) == cp(true,2))   // ORI.B  #imm,D0 (.B = opword + 1 imm word)
    assert(classify(0x0240) == cp(true,2))   // ANDI.W #imm,D0 (.W = opword + 1 imm word)
    assert(classify(0x0480) == cp(true,3))   // SUBI.L #imm,D0 (.L = opword + 2 imm words)
    assert(classify(0x0680) == cp(true,3))   // ADDI.L #imm,D0
    assert(classify(0x0A40) == cp(true,2))   // EORI.W #imm,D0
    assert(classify(0x0C80) == cp(true,3))   // CMPI.L #imm,D0
  }
  test("line-0 ANDI/ORI/EORI #imm,CCR/SR (...00/01 111100) -> simple len2; CMPI-to-ccr complex") {
    assert(classify(0x023C) == cp(true,2))   // ANDI #imm,CCR
    assert(classify(0x003C) == cp(true,2))   // ORI  #imm,CCR
    assert(classify(0x0A3C) == cp(true,2))   // EORI #imm,CCR
    // ANDI/ORI/EORI #imm,SR (word, privileged): now a commit-time sysOp (cluster-6
    // exception/priv triage) -- same 1-imm-word framing as the CCR (byte) form.
    assert(classify(0x027C) == cp(true,2))   // ANDI #imm,SR
    assert(classify(0x007C) == cp(true,2))   // ORI  #imm,SR
    assert(classify(0x0A7C) == cp(true,2))   // EORI #imm,SR
    assert(classify(0x0C3C) == cp(false,0))  // CMPI #imm,<#imm> (no CMPI-to-CCR) -> illegal
  }
  test("line-0 memory-dest immediate (RMW) + illegal size + out-of-scope") {
    assert(classify(0x0010) == cp(true,2))   // ORI.B #imm,(A0) — opword + imm word + (An) ext0
    assert(classify(0x0610) == cp(true,2))   // ADDI.B #imm,(A0)
    assert(classify(0x0668) == cp(true,3))   // ADDI.W #imm,(d16,A0) — opword + imm + disp16
    assert(classify(0x04B9) == cp(true,5))   // SUBI.L #imm,(xxx).L — opword + 2 imm + abs32
    assert(classify(0x0618) == cp(true,2))   // ADDI.B #imm,(A0)+ -> RMW postinc (opword + imm, 0 ea ext)
    assert(classify(0x00C0) == cp(false,0))  // ss=11 illegal size
    assert(classify(0x0840) == cp(true,2))   // BCHG #n,D0 (static bit-op, opmode 4) — opword + bit word
  }
  test("BTST PC-relative carve-out is exhaustive and excludes all three write bit-ops") {
    // Dynamic encoding: 0000 ddd 1 tt 111 rrr. Sweep all 8 bit-number Dn fields,
    // all 4 bit operations and both PC-relative EA encodings. Only tt=00 (BTST)
    // is read-only and legal; BCHG/BCLR/BSET require a data-alterable destination.
    for {
      dn    <- 0 until 8
      tt    <- 0 until 4
      pcReg <- Seq(2, 3)                    // (d16,PC), (d8,PC,Xn)
    } {
      val op = (dn << 9) | 0x0100 | (tt << 6) | 0x0038 | pcReg
      val expected = if (tt == 0) cp(true, 2) else cp(false, 0)
      assert(classify(op) == expected,
        f"dynamic bit-op D$dn tt=$tt pcReg=$pcReg op=0x$op%04x")
    }

    // Static encoding: 0000 1000 tt 111 rrr + bit-number word. The same BTST-only
    // legality applies, with one additional word before the PC-relative EA extension.
    for {
      tt    <- 0 until 4
      pcReg <- Seq(2, 3)
    } {
      val op = 0x0800 | (tt << 6) | 0x0038 | pcReg
      val expected = if (tt == 0) cp(true, 3) else cp(false, 0)
      assert(classify(op) == expected,
        f"static bit-op tt=$tt pcReg=$pcReg op=0x$op%04x")
    }
  }
  test("NBCD register partition is exactly 0x4800-0x4807; memory forms stay deferred") {
    for (dn <- 0 until 8) {
      val op = 0x4800 | dn
      assert(classify(op) == cp(true, 1), f"NBCD D$dn op=0x$op%04x")
    }
    // Mode 001 at 0x4808-0x480f is LINK.L on 020+, not NBCD. The actual NBCD
    // memory-EA modes 2..7 are a separate unimplemented RMW/microcode slice.
    for (mode <- 2 until 8) {
      val op = 0x4800 | (mode << 3)
      assert(classify(op) == cp(false, 0), f"deferred NBCD memory mode=$mode op=0x$op%04x")
    }
  }
  test("LINK.L partition is exactly 0x4808-0x480f and carries disp32") {
    for (an <- 0 until 8) {
      val op = 0x4808 | an
      assert(classify(op) == cp(true, 3), f"LINK.L A$an,#disp32 op=0x$op%04x")
    }
    // Pin both partition boundaries: the preceding eight words are NBCD Dn and the
    // following word starts the still-deferred NBCD memory-EA partition.
    assert(classify(0x4807) == cp(true, 1))
    assert(classify(0x4810) == cp(false, 0))
  }
  test("68020+ TST An partition admits WORD/LONG only and no write-capable unary op") {
    for {
      size <- 0 until 4
      an   <- 0 until 8
    } {
      val op = 0x4A00 | (size << 6) | 0x0008 | an
      val expected = if (size == 1 || size == 2) cp(true, 1) else cp(false, 0)
      assert(classify(op) == expected, f"TST size=$size A$an op=0x$op%04x")
    }

    // Mode 001 is legal here only because TST is read-only. These four sibling
    // unary families write their operand and must keep rejecting address registers.
    for {
      unary <- Seq(0x0, 0x2, 0x4, 0x6)       // NEGX, CLR, NEG, NOT
      size  <- 0 until 3
      an    <- 0 until 8
    } {
      val op = 0x4000 | (unary << 8) | (size << 6) | 0x0008 | an
      assert(classify(op) == cp(false, 0),
        f"write unary=$unary size=$size A$an op=0x$op%04x")
    }
  }
  test("TAS register and memory-alterable partition matches the implemented RMW framing") {
    for (dn <- 0 until 8) {
      val op = 0x4AC0 | dn
      assert(classify(op) == cp(true, 1), f"TAS D$dn op=0x$op%04x")
    }

    for {
      mode <- 2 to 6
      reg  <- 0 until 8
    } {
      val op = 0x4AC0 | (mode << 3) | reg
      val ext = if (mode == 5 || mode == 6) 1 else 0
      assert(classify(op) == cp(true, 1 + ext),
        f"TAS memory mode=$mode reg=$reg op=0x$op%04x")
    }
    assert(classify(0x4AF8) == cp(true, 2), "TAS (xxx).W")
    assert(classify(0x4AF9) == cp(true, 3), "TAS (xxx).L")

    // An-direct and every non-alterable mode-7 encoding stay rejected. In particular,
    // PC-relative and immediate are readable EAs but cannot be TAS destinations.
    for (reg <- 0 until 8) {
      val op = 0x4AC8 | reg
      assert(classify(op) == cp(false, 0), f"illegal TAS A$reg op=0x$op%04x")
    }
    for (reg <- 2 until 8) {
      val op = 0x4AF8 | reg
      assert(classify(op) == cp(false, 0), f"illegal TAS mode7 reg=$reg op=0x$op%04x")
    }
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
