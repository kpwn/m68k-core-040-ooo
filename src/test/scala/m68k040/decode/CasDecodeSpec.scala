package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CAS / CAS2 (020+ atomic compare-and-swap) decode + predecode framing.
  *
  * Encoding (cross-checked vs `m68k-linux-gnu-as -m68040` + objdump):
  *   CAS.B (%a0)  0x0AD0 0042   opword 0000 1010 11 010 000 ; ext Du=ext[8:6] Dc=ext[2:0]
  *   CAS.W (%a0)  0x0CD0 ...     op[10:9]=10
  *   CAS.L (%a0)  0x0ED0 ...     op[10:9]=11
  *   CAS.L 32(%a3) 0x0EEB 0144 0020  (d16,An) -> opword + 1 ext + 1 EA disp word
  *   CAS2.L %d0:%d1,%d2:%d3,(%a0):(%a1)  0x0EFC 8080 90C1  (mode7/reg4, 2 ext words)
  *   CAS2.W ...                          0x0CFC ...
  * CAS family: op[15:11]=00001, op[8]=0, op[7:6]=11, op[10:9]=size (=/=00). CAS2 adds
  * op[5:0]=111100. Both route through the v2 microcode engine (microcoded + ucEntry).
  * A non-memory-alterable CAS EA (Dn/An/(An)+/-(An)/PC-rel/#imm) decodes ILLEGAL. */
class CasDecodeSpec extends AnyFunSuite {

  // ── OperationDecoder level ──────────────────────────────────────────────────
  class DecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def runDec(op: Int)(check: DecDut => Unit): Unit =
    SimConfig.withVerilator.compile(new DecDut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  // CAS opwords: 0000 1 ss 0 11 mmm rrr. ss: 01=.B,10=.W,11=.L.
  def casOp(ss: Int, mode: Int, reg: Int): Int =
    (0x08 << 8) | (ss << 9) | (3 << 6) | (mode << 3) | reg
  def cas2Op(ss: Int): Int = casOp(ss, 7, 4)   // mode 7 / reg 4

  test("CAS.L (%a0) (0x0ED0): microcoded, CAS_ENTRY, op=CASOP size=LONG, not illegal", VerilatorTest) {
    runDec(0x0ED0) { dut =>
      assert(!dut.o.illegal.toBoolean, "CAS.L (An) not illegal")
      assert(dut.o.microcoded.toBoolean, "microcoded")
      assert(dut.o.ucEntry.toInt == Microcode.CAS_ENTRY, s"ucEntry == CAS_ENTRY (${Microcode.CAS_ENTRY})")
      assert(dut.o.op.toEnum == DecOp.CASOP, "op = CASOP")
      assert(dut.o.size.toEnum == Size.LONG, "size = LONG")
      assert(dut.o.cluster.toEnum == Cluster.INT, "cluster = INT")
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean, "writes NZVC, not X")
    }
  }
  test("CAS.B/.W sizes (0x0AD0/0x0CD0): microcoded BYTE/WORD", VerilatorTest) {
    runDec(0x0AD0) { dut =>
      assert(dut.o.microcoded.toBoolean && dut.o.op.toEnum == DecOp.CASOP, "CAS.B CASOP")
      assert(dut.o.size.toEnum == Size.BYTE, "size = BYTE")
      assert(dut.o.ucEntry.toInt == Microcode.CAS_ENTRY)
    }
    runDec(0x0CD0) { dut =>
      assert(dut.o.microcoded.toBoolean && dut.o.size.toEnum == Size.WORD, "CAS.W WORD")
    }
  }
  test("CAS.L (d16,An) (0x0EEB): microcoded (memory-alterable control mode 5)", VerilatorTest) {
    runDec(casOp(3, 5, 3)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "(d16,An) ok")
      assert(dut.o.ucEntry.toInt == Microcode.CAS_ENTRY)
    }
  }
  test("CAS.L (xxx).W / (xxx).L (mode7/reg0,1): microcoded", VerilatorTest) {
    runDec(casOp(3, 7, 0)) { dut => assert(dut.o.microcoded.toBoolean, "(xxx).W ok") }
    runDec(casOp(3, 7, 1)) { dut => assert(dut.o.microcoded.toBoolean, "(xxx).L ok") }
  }
  // CAS is memory-ALTERABLE (Musashi mask `A+-DXWL...`): (An)+ and -(An) ARE legal.
  test("CAS.L (%a0)+ (mode3) / -(%a0) (mode4): microcoded (memory-alterable auto modes)", VerilatorTest) {
    runDec(casOp(3, 3, 0)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "CAS (An)+ legal")
      assert(dut.o.ucEntry.toInt == Microcode.CAS_ENTRY)
    }
    runDec(casOp(3, 4, 0)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "CAS -(An) legal")
      assert(dut.o.ucEntry.toInt == Microcode.CAS_ENTRY)
    }
  }

  // ── Illegal CAS EAs (NOT memory-alterable) ──────────────────────────────────
  // NOTE: mode7/reg4 (op[5:0]=111100) is the CAS2 marker (legal). (An)+ (3) and -(An) (4)
  // ARE memory-alterable (legal). Only Dn/An/PC-rel are illegal for CAS.
  test("CAS Dn / An / (d16,PC) / (d8,PC,Xn) -> ILLEGAL", VerilatorTest) {
    val badModes = Seq(
      (0, 1, "Dn"), (1, 2, "An"), (7, 2, "(d16,PC)"), (7, 3, "(d8,PC,Xn)"))
    for ((mode, reg, name) <- badModes) {
      runDec(casOp(3, mode, reg)) { dut =>
        assert(dut.o.illegal.toBoolean, s"CAS $name must be ILLEGAL")
        assert(!dut.o.microcoded.toBoolean, s"CAS $name not microcoded")
      }
    }
  }

  // ── CAS2 ────────────────────────────────────────────────────────────────────
  test("CAS2.L (0x0EFC): microcoded, CAS2_ENTRY, op=CASOP size=LONG", VerilatorTest) {
    runDec(0x0EFC) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "CAS2.L ok")
      assert(dut.o.ucEntry.toInt == Microcode.CAS2_ENTRY, s"ucEntry == CAS2_ENTRY (${Microcode.CAS2_ENTRY})")
      assert(dut.o.op.toEnum == DecOp.CASOP, "op = CASOP")
      assert(dut.o.size.toEnum == Size.LONG, "size = LONG")
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("CAS2.W (0x0CFC): microcoded WORD", VerilatorTest) {
    runDec(0x0CFC) { dut =>
      assert(dut.o.microcoded.toBoolean && dut.o.size.toEnum == Size.WORD, "CAS2.W WORD")
      assert(dut.o.ucEntry.toInt == Microcode.CAS2_ENTRY)
    }
  }

  // ── Predecode framing (RTL PredecodeWord.classify) ──────────────────────────
  class PreDut extends Component {
    val op    = in Bits (16 bits)
    val ext1  = in Bits (16 bits)
    val ext2  = in Bits (16 bits)
    val simple = out Bool ()
    val len    = out UInt (4 bits)
    val r = m68k040.frontend.PredecodeWord.classify(op, ext1, ext2)
    simple := r.simple
    len    := r.lenWords
  }
  def runPre(op: Int, ext1: Int = 0, ext2: Int = 0)(check: PreDut => Unit): Unit =
    SimConfig.withVerilator.compile(new PreDut).doSim { dut =>
      dut.op #= op; dut.ext1 #= ext1; dut.ext2 #= ext2; sleep(1); check(dut)
    }

  test("predecode: CAS (An) -> simple len 2 (opword + 1 ext)", VerilatorTest) {
    runPre(0x0ED0) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "CAS (An) len 2") }
  }
  test("predecode: CAS (An)+ / -(An) -> simple len 2 (no EA ext, auto side effect)", VerilatorTest) {
    runPre(casOp(3, 3, 0)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "CAS (An)+ len 2") }
    runPre(casOp(3, 4, 0)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "CAS -(An) len 2") }
  }
  test("predecode: CAS (d16,An) -> simple len 3 (opword + ext + disp)", VerilatorTest) {
    runPre(casOp(3, 5, 3)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 3, "CAS (d16,An) len 3") }
  }
  test("predecode: CAS (xxx).L -> simple len 4 (opword + ext + 2 disp)", VerilatorTest) {
    runPre(casOp(3, 7, 1)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 4, "CAS (xxx).L len 4") }
  }
  test("predecode: CAS2 -> simple len 3 (opword + 2 ext)", VerilatorTest) {
    runPre(0x0EFC) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 3, "CAS2 len 3") }
    runPre(0x0CFC) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 3, "CAS2.W len 3") }
  }
  test("predecode: illegal CAS EA (Dn) -> COMPLEX (not simple)", VerilatorTest) {
    runPre(casOp(3, 0, 1)) { dut => assert(!dut.simple.toBoolean, "CAS Dn -> complex") }
  }
}
