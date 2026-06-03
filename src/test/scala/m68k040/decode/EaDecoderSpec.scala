package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class EaDecoderSpec extends AnyFunSuite {
  class Dut extends Component {
    val eaField = in Bits (6 bits)
    val size    = in(Size())
    val words   = in(Vec(Bits(16 bits), 5))
    val out0    = out(EaSpec())
    out0 := EaDecoder.decode(eaField, size, words)
  }
  def run(f: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim { dut =>
    dut.words.foreach(_ #= 0); f(dut)
  }
  test("Dn -> DATAREG reg n", VerilatorTest) { run { dut =>
    dut.eaField #= 0x03 /*mode0 reg3*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.DATAREG && dut.out0.reg.toInt == 3)
  }}
  test("An -> ADDRREG reg 8+n", VerilatorTest) { run { dut =>
    dut.eaField #= 0x0A /*mode1 reg2*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.ADDRREG && dut.out0.reg.toInt == 10)
  }}
  test("#imm.W sign-extends from words(1)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3C /*mode7 reg4*/; dut.size #= Size.WORD; dut.words(1) #= 0xFFFE; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.IMM && dut.out0.imm.toLong == 0xFFFFFFFEL)
  }}
  test("#imm.L is words(1)##words(2)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3C; dut.size #= Size.LONG; dut.words(1) #= 0x1234; dut.words(2) #= 0x5678; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.IMM && dut.out0.imm.toLong == 0x12345678L)
  }}
  test("memory mode -> MEMSIMPLE (reserved, not reg/imm)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x10 /*mode2 (An)*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
  }}
}
