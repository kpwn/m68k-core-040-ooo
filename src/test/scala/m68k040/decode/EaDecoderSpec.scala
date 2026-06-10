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
  // ── (An)+ postincrement (mode 3) ──────────────────────────────────────────────
  test("(A2)+ .L -> MEMSIMPLE POSTINC base=A2 delta=4", VerilatorTest) { run { dut =>
    dut.eaField #= 0x1A /*mode3 reg2*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean && dut.out0.base.toInt == 10)
    assert(dut.out0.autoMode.toEnum == EaAuto.POSTINC && dut.out0.autoDelta.toInt == 4)
  }}
  test("(A2)+ .W -> POSTINC delta=2", VerilatorTest) { run { dut =>
    dut.eaField #= 0x1A; dut.size #= Size.WORD; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.POSTINC && dut.out0.autoDelta.toInt == 2)
  }}
  test("(A2)+ .B -> POSTINC delta=1", VerilatorTest) { run { dut =>
    dut.eaField #= 0x1A; dut.size #= Size.BYTE; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.POSTINC && dut.out0.autoDelta.toInt == 1)
  }}
  // ── -(An) predecrement (mode 4) ───────────────────────────────────────────────
  test("-(A2) .L -> MEMSIMPLE PREDEC base=A2 delta=4", VerilatorTest) { run { dut =>
    dut.eaField #= 0x22 /*mode4 reg2*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean && dut.out0.base.toInt == 10)
    assert(dut.out0.autoMode.toEnum == EaAuto.PREDEC && dut.out0.autoDelta.toInt == 4)
  }}
  test("-(A2) .W -> PREDEC delta=2", VerilatorTest) { run { dut =>
    dut.eaField #= 0x22; dut.size #= Size.WORD; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.PREDEC && dut.out0.autoDelta.toInt == 2)
  }}
  // ── A7 byte even-keeping rule (reg==7) ────────────────────────────────────────
  test("-(A7) .B -> PREDEC delta=2 (keep SP even)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x27 /*mode4 reg7=A7*/; dut.size #= Size.BYTE; sleep(1)
    assert(dut.out0.base.toInt == 15)
    assert(dut.out0.autoMode.toEnum == EaAuto.PREDEC && dut.out0.autoDelta.toInt == 2)
  }}
  test("(A7)+ .B -> POSTINC delta=2 (keep SP even)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x1F /*mode3 reg7=A7*/; dut.size #= Size.BYTE; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.POSTINC && dut.out0.autoDelta.toInt == 2)
  }}
  test("(A7)+ .W -> POSTINC delta=2 (normal sizeBytes on A7)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x1F; dut.size #= Size.WORD; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.POSTINC && dut.out0.autoDelta.toInt == 2)
  }}
  test("-(A7) .L -> PREDEC delta=4 (normal sizeBytes on A7)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x27; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.PREDEC && dut.out0.autoDelta.toInt == 4)
  }}
  // ── non-auto EAs leave autoMode NONE ──────────────────────────────────────────
  test("(An) leaves autoMode NONE", VerilatorTest) { run { dut =>
    dut.eaField #= 0x10; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.autoMode.toEnum == EaAuto.NONE)
  }}
}
