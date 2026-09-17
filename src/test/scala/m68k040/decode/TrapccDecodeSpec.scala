package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** TRAPcc decode: `0101 cccc 11 111 ttt`
  * - ttt=4 (100): no operand (1 word). Encoding: op[2:0]=4.
  * - ttt=2 (010): #data16 (2 words). Encoding: op[2:0]=2.
  * - ttt=3 (011): #data32 (3 words). Encoding: op[2:0]=3.
  * - ttt=0/1 are the overlapping absolute-W/L Scc memory encodings, not TRAPcc.
  * - ttt=5/6/7 are illegal.
  * The µop is a branch-class isCondTrap with cond=cccc, reads NZVC, fault vector 7
  * on taken. No register write, no CCR write. faultUsesNextPc (stacks NEXT instr PC). */
class TrapccDecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }

  def drive(dut: Dut, op: Int, len: Int, word1: Int = 0, word2: Int = 0): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000
    dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= word1; dut.pkt.words(2) #= word2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }

  def run(check: Dut => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ── Form 1: TRAPF (cc=F=1) no-operand (ttt=4) ─────────────────────────────
  // Encoding: 0101 0001 11 111 100 = 0x51FC. cond=1, ttt=4.
  // No-operand -> lenWords=1, nextPc=0x1002.
  test("TRAPF (no-operand, ttt=4): isCondTrap, cond=1, 1 word, faultUsesNextPc", VerilatorTest) {
    run { dut =>
      drive(dut, 0x51FC, len = 1)
      sleep(1)
      assert(dut.uop.isCondTrap.toBoolean, "TRAPcc must set isCondTrap")
      assert(dut.uop.isBranch.toBoolean, "TRAPcc routes to the branch EU (isBranch)")
      assert(dut.uop.cond.toInt == 1, s"cond must be 1 (F), got ${dut.uop.cond.toInt}")
      assert(dut.uop.readsNzvc.toBoolean, "TRAPcc reads NZVC for the condition")
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "TRAPcc writes no flags")
      assert(!dut.uop.dstValid.toBoolean && !dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean,
             "TRAPcc has no integer operands")
      assert(dut.uop.faultUsesNextPc.toBoolean, "TRAPcc is not restartable -> stacks nextPc")
      assert(!dut.uop.faulted.toBoolean, "TRAPcc is conditional: fault set at execute, not decode")
      assert(!dut.uop.unimplemented.toBoolean, "TRAPcc is implemented")
      assert(!dut.uop.isScc.toBoolean && !dut.uop.isDbcc.toBoolean, "not Scc / DBcc")
      // nextPc should be pc + 1*2 = 0x1002
      assert(dut.uop.lenWords.toInt == 1,
             s"lenWords must be 1 (nextPc = pc+2 = 0x1002) for the no-operand form, got ${dut.uop.lenWords.toInt}")
    }
  }

  // ── Form 2: TRAPEQ (#data16, ttt=2) ────────────────────────────────────────
  // Encoding: 0101 0111 11 111 010 = 0x57FA. cond=7 (EQ), ttt=2.
  // Word form -> lenWords=2, nextPc=0x1004.
  test("TRAPEQ (ttt=2, #data16): isCondTrap, cond=7, 2 words, nextPc=pc+4", VerilatorTest) {
    run { dut =>
      drive(dut, 0x57FA, len = 2, word1 = 0xDEAD)
      sleep(1)
      assert(dut.uop.isCondTrap.toBoolean && dut.uop.isBranch.toBoolean)
      assert(dut.uop.cond.toInt == 7, s"cond must be 7 (EQ), got ${dut.uop.cond.toInt}")
      assert(dut.uop.readsNzvc.toBoolean)
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean)
      assert(!dut.uop.dstValid.toBoolean)
      assert(dut.uop.faultUsesNextPc.toBoolean)
      assert(!dut.uop.faulted.toBoolean && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.lenWords.toInt == 2,
             s"lenWords must be 2 (nextPc = pc+4 = 0x1004) for the word form, got ${dut.uop.lenWords.toInt}")
    }
  }

  // ── Form 3: TRAPVS (#data32, ttt=3) ────────────────────────────────────────
  // Encoding: 0101 1001 11 111 011 = 0x59FB. cond=9 (VS), ttt=3.
  // Long form -> lenWords=3, nextPc=0x1006.
  test("TRAPVS (ttt=3, #data32): isCondTrap, cond=9, 3 words, nextPc=pc+6", VerilatorTest) {
    run { dut =>
      drive(dut, 0x59FB, len = 3, word1 = 0x1234, word2 = 0x5678)
      sleep(1)
      assert(dut.uop.isCondTrap.toBoolean && dut.uop.isBranch.toBoolean)
      assert(dut.uop.cond.toInt == 9, s"cond must be 9 (VS), got ${dut.uop.cond.toInt}")
      assert(dut.uop.readsNzvc.toBoolean)
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean)
      assert(!dut.uop.dstValid.toBoolean)
      assert(dut.uop.faultUsesNextPc.toBoolean)
      assert(!dut.uop.faulted.toBoolean && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.lenWords.toInt == 3,
             s"lenWords must be 3 (nextPc = pc+6 = 0x1006) for the long form, got ${dut.uop.lenWords.toInt}")
    }
  }

  // ── Form 4: TRAPT (cc=T=0) no-operand — always-true condition ───────────────
  // Encoding: 0101 0000 11 111 100 = 0x50FC. cond=0 (T).
  test("TRAPT (cond=T=0, ttt=4): cond=0, 1 word", VerilatorTest) {
    run { dut =>
      drive(dut, 0x50FC, len = 1)
      sleep(1)
      assert(dut.uop.isCondTrap.toBoolean && dut.uop.cond.toInt == 0)
      assert(dut.uop.lenWords.toInt == 1)
    }
  }

  // ── ttt=0 overlaps Scc (xxx).W; it is not a TRAPcc encoding ───────────────
  test("mode7 ttt=0 decodes as absolute-W Scc", VerilatorTest) {
    run { dut =>
      drive(dut, 0x59F8, len = 2, word1 = 0x2000)
      sleep(1)
      assert(dut.uop.isScc.toBoolean && !dut.uop.isCondTrap.toBoolean)
      assert(!dut.uop.unimplemented.toBoolean && dut.uop.cond.toInt == 9)
      assert(dut.uop.dstValid.toBoolean && dut.uop.dstReg.toInt == MicroOpAssembler.T1)
    }
  }

  // ── ttt=1 overlaps Scc (xxx).L; it is not a TRAPcc encoding ───────────────
  test("mode7 ttt=1 decodes as absolute-L Scc", VerilatorTest) {
    run { dut =>
      drive(dut, 0x59F9, len = 3, word1 = 0x0000, word2 = 0x2000)
      sleep(1)
      assert(dut.uop.isScc.toBoolean && !dut.uop.isCondTrap.toBoolean)
      assert(!dut.uop.unimplemented.toBoolean && dut.uop.cond.toInt == 9)
      assert(dut.uop.dstValid.toBoolean && dut.uop.dstReg.toInt == MicroOpAssembler.T1)
    }
  }

  // ── Scc (mode=0) still works: SEQ D2 = 0x57C2 ─────────────────────────────
  test("SEQ D2 (0x57C2): isScc still decodes correctly (TRAPcc decode doesn't interfere)", VerilatorTest) {
    run { dut =>
      drive(dut, 0x57C2, len = 1)
      sleep(1)
      assert(dut.uop.isScc.toBoolean, "Scc must still decode correctly after TRAPcc addition")
      assert(!dut.uop.isCondTrap.toBoolean, "Scc must NOT set isCondTrap")
      assert(dut.uop.cond.toInt == 7)
    }
  }

  // ── DBcc (mode=1) still works: DBNE D0,disp = 0x56C8 ─────────────────────
  test("DBNE D0 (0x56C8): isDbcc still decodes correctly", VerilatorTest) {
    run { dut =>
      drive(dut, 0x56C8, len = 2, word1 = 0x0008)
      sleep(1)
      assert(dut.uop.isDbcc.toBoolean, "DBcc must still decode correctly after TRAPcc addition")
      assert(!dut.uop.isCondTrap.toBoolean, "DBcc must NOT set isCondTrap")
      assert(dut.uop.cond.toInt == 6)
    }
  }
}
