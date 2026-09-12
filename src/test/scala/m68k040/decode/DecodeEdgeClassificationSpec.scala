package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Pins the CLASSIFICATION of the encodings where "obvious" refactors of the
  * remaining opword re-matches would silently change behaviour.
  *
  * Two rewrites look mechanical and are NOT:
  *
  *  1. `sccMemBad = isLine5 && ss==11 && mode=/=0 && mode=/=1 && !isTrapccOp`
  *     tempts you into `isSccOp && klass =/= DATAREG`. It differs on line-5
  *     ss==11 mode 111 reg 5/6/7 (RESERVED): the old term keeps them in
  *     sccMemBad (`!isTrapccOp` is true, since reg is not 2/3/4), while the SCC
  *     form excludes every mode7 reg>=2.
  *
  *  2. `l0EaIsIdx = mode === 110` tempts you into the EA's `indexValid`. That is
  *     format-BLIND vs format-AWARE: a FULL-format extension word with IS=1
  *     suppresses the index, so `indexValid` is False while `mode === 110` holds.
  *
  * These tests assert the behaviour that ships today. If a refactor flips one,
  * that is the refactor being wrong -- not this file.
  */
class DecodeEdgeClassificationSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // line-5 ss==11 (0xC0) mode 111 (0x38) reg 5/6/7 -- reserved mode-7 sub-forms.
  // Neither Scc (mode7 reg>=2 is not an Scc destination) nor TRAPcc (ttt must be 2/3/4).
  test("line-5 ss=11 mode7 reg5/6/7 (reserved) stay ILLEGAL", VerilatorTest) { run { dut =>
    for (reg <- Seq(5, 6, 7)) {
      drive(dut, 0x50C0 | 0x38 | reg); sleep(1)
      assert(dut.uop.unimplemented.toBoolean,
        f"opword 0x${0x50C0 | 0x38 | reg}%04X (line5 ss=11 mode7 reg$reg) must classify ILLEGAL")
    }
  }}

  // Control: the TRAPcc ttt encodings (reg 2/3/4) next door are NOT illegal --
  // this is what makes the reserved cases above a real boundary and not a
  // vacuous assertion.
  test("line-5 ss=11 mode7 reg2/3/4 (TRAPcc) are NOT illegal", VerilatorTest) { run { dut =>
    for ((reg, len) <- Seq((4, 1), (2, 2), (3, 3))) {
      drive(dut, 0x50C0 | 0x38 | reg, w1 = 0, w2 = 0, len = len); sleep(1)
      assert(!dut.uop.unimplemented.toBoolean,
        f"opword 0x${0x50C0 | 0x38 | reg}%04X (TRAPcc ttt=$reg) must NOT classify ILLEGAL")
    }
  }}
}
