package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** MOVES (010+ PRIVILEGED move to/from alternate address space) decode + predecode.
  *
  * Encoding (cross-checked vs `m68k-linux-gnu-as -m68040` + objdump):
  *   moves.l %d0,(%a0)   0x0E90 0800   opword 0000 1110 10 010 000 ; ext dr=ext[11]=1,
  *                                     A/D=ext[15]=0, reg=ext[14:12]=0 -> D0
  *   moves.l (%a0),%d1   0x0E90 1000   ext dr=0 (read), reg=1 -> D1
  *   moves.l (%a0),%a3   0x0E90 B000   ext A/D=1 -> A3
  *   moves.w/.b          0x0E50 / 0x0E10  op[7:6]=01/00
  *   moves.l (4,%a0),%d1 0x0EA8 1000 0004  (d16,An) -> opword + 1 ext + 1 EA disp word
  *   moves.l 0x3000,%d1  0x0EB8 1000 3000  (xxx).W -> opword + 1 ext + 1 abs word
  * MOVES family: op[15:8]=0x0E (op[15:11]=00001, op[10:8]=110), op[7:6]=ss in {00,01,10}.
  * DISJOINT from CAS (which needs op[7:6]=11) and the line-0 immediate/bit/CMP2 arms.
  * EA = memory-ALTERABLE (Musashi mask `A+-DXWL...`): (An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)/
  * (xxx).W/.L. Reject Dn/An-direct/PC-rel/#imm. Routes through the v2 microcode engine
  * (microcoded + ucEntry), with needsSupervisor set (ROB vector-8 if S==0). */
class MovesDecodeSpec extends AnyFunSuite {

  // ── OperationDecoder level ──────────────────────────────────────────────────
  class DecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def runDec(op: Int)(check: DecDut => Unit): Unit =
    SimConfig.withVerilator.compile(new DecDut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  // MOVES opwords: 0000 1110 ss mmmrrr. ss: 00=.B,01=.W,10=.L.
  def movesOp(ss: Int, mode: Int, reg: Int): Int =
    (0x0E << 8) | (ss << 6) | (mode << 3) | reg

  // privilege (needsSupervisor) is carried on the resolved MICROCODE µop (via Ctx), not in
  // OpSpec — it is verified by the user-mode -> vector-8 lock-step (Task 4), not here.
  test("MOVES.L (%a0) (0x0E90): microcoded, op=MOVES size=LONG, not illegal, NO flags", VerilatorTest) {
    runDec(0x0E90) { dut =>
      assert(!dut.o.illegal.toBoolean, "MOVES.L (An) not illegal")
      assert(dut.o.microcoded.toBoolean, "microcoded")
      assert(dut.o.op.toEnum == DecOp.MOVES, "op = MOVES")
      assert(dut.o.size.toEnum == Size.LONG, "size = LONG")
      assert(dut.o.cluster.toEnum == Cluster.INT, "cluster = INT")
      assert(!dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean, "MOVES touches NO flags")
    }
  }
  test("MOVES.B/.W sizes (0x0E10/0x0E50): microcoded BYTE/WORD", VerilatorTest) {
    runDec(0x0E10) { dut =>
      assert(dut.o.microcoded.toBoolean && dut.o.op.toEnum == DecOp.MOVES, "MOVES.B MOVES")
      assert(dut.o.size.toEnum == Size.BYTE, "size = BYTE")
    }
    runDec(0x0E50) { dut =>
      assert(dut.o.microcoded.toBoolean && dut.o.size.toEnum == Size.WORD, "MOVES.W WORD")
    }
  }
  test("MOVES.L (d16,An) (mode5): microcoded (memory-alterable control mode)", VerilatorTest) {
    runDec(movesOp(2, 5, 3)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "(d16,An) ok")
      assert(dut.o.op.toEnum == DecOp.MOVES)
    }
  }
  test("MOVES.L (xxx).W / (xxx).L (mode7/reg0,1): microcoded", VerilatorTest) {
    runDec(movesOp(2, 7, 0)) { dut => assert(dut.o.microcoded.toBoolean, "(xxx).W ok") }
    runDec(movesOp(2, 7, 1)) { dut => assert(dut.o.microcoded.toBoolean, "(xxx).L ok") }
  }
  // MOVES is memory-ALTERABLE (Musashi mask `A+-DXWL...`): (An)+ and -(An) ARE legal.
  test("MOVES.L (%a0)+ (mode3) / -(%a0) (mode4): microcoded (memory-alterable auto modes)", VerilatorTest) {
    runDec(movesOp(2, 3, 0)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "MOVES (An)+ legal")
      assert(dut.o.op.toEnum == DecOp.MOVES)
    }
    runDec(movesOp(2, 4, 0)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "MOVES -(An) legal")
    }
  }
  test("MOVES.L (d8,An,Xn) (mode6): microcoded", VerilatorTest) {
    runDec(movesOp(2, 6, 0)) { dut =>
      assert(!dut.o.illegal.toBoolean && dut.o.microcoded.toBoolean, "(d8,An,Xn) ok")
    }
  }

  // ── Illegal MOVES EAs (NOT memory-alterable) ────────────────────────────────
  test("MOVES Dn / An / (d16,PC) / (d8,PC,Xn) / #imm -> ILLEGAL", VerilatorTest) {
    val badModes = Seq(
      (0, 1, "Dn"), (1, 2, "An"), (7, 2, "(d16,PC)"), (7, 3, "(d8,PC,Xn)"), (7, 4, "#imm"))
    for ((mode, reg, name) <- badModes) {
      runDec(movesOp(2, mode, reg)) { dut =>
        assert(dut.o.illegal.toBoolean, s"MOVES $name must be ILLEGAL")
        assert(!dut.o.microcoded.toBoolean, s"MOVES $name not microcoded")
      }
    }
  }

  // op[7:6]=11 (=CAS family) is NOT MOVES — ensure no aliasing.
  test("op[7:6]=11 with op[15:8]=0x0E stays CAS (not MOVES)", VerilatorTest) {
    runDec((0x0E << 8) | (3 << 6) | (2 << 3) | 0) { dut =>   // 0x0ED0 = CAS.L (%a0)
      assert(dut.o.op.toEnum != DecOp.MOVES, "0x0ED0 is CAS, not MOVES")
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

  test("predecode: MOVES (An) -> simple len 2 (opword + 1 ext)", VerilatorTest) {
    runPre(0x0E90) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "MOVES (An) len 2") }
  }
  test("predecode: MOVES (An)+ / -(An) -> simple len 2 (no EA ext, auto side effect)", VerilatorTest) {
    runPre(movesOp(2, 3, 0)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "MOVES (An)+ len 2") }
    runPre(movesOp(2, 4, 0)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "MOVES -(An) len 2") }
  }
  test("predecode: MOVES (d16,An) -> simple len 3 (opword + ext + disp)", VerilatorTest) {
    runPre(movesOp(2, 5, 3)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 3, "MOVES (d16,An) len 3") }
  }
  test("predecode: MOVES (xxx).L -> simple len 4 (opword + ext + 2 disp)", VerilatorTest) {
    runPre(movesOp(2, 7, 1)) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 4, "MOVES (xxx).L len 4") }
  }
  test("predecode: MOVES .B/.W (An) -> simple len 2", VerilatorTest) {
    runPre(0x0E10) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "MOVES.B (An) len 2") }
    runPre(0x0E50) { dut => assert(dut.simple.toBoolean && dut.len.toInt == 2, "MOVES.W (An) len 2") }
  }
  test("predecode: illegal MOVES EA (Dn) -> COMPLEX (not simple)", VerilatorTest) {
    runPre(movesOp(2, 0, 1)) { dut => assert(!dut.simple.toBoolean, "MOVES Dn -> complex") }
  }
}
