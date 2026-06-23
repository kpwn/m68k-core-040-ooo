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
    val words   = in(Vec(Bits(16 bits), 6))
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

  // ── Brief-format indexed (d8,An,Xn*scale) (mode 6) ────────────────────────────
  // ext word layout: D/A(15) | Xn(14:12) | W/L(11) | scale(10:9) | 0(8 brief) | d8(7:0)
  test("(4,A0,D1.w*2) -> MEMSIMPLE INDEXED base=A0 disp=4 index=D1 .W scale=1(=*2)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30 /*mode6 reg0=A0*/; dut.size #= Size.LONG
    dut.words(1) #= 0x1204 /* D1, .W, scale*2, d8=4 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean && dut.out0.base.toInt == 8)   // A0
    assert(dut.out0.disp.toLong == 4)
    assert(dut.out0.indexValid.toBoolean && dut.out0.indexReg.toInt == 1)   // D1
    assert(!dut.out0.indexLong.toBoolean && dut.out0.indexScale.toInt == 1) // .W, *2
    assert(!dut.out0.pcRel.toBoolean)
  }}
  test("(-2,A3,A5.l*8) -> base=A3 negative disp, index=A5 .L scale=3(=*8)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x33 /*mode6 reg3=A3*/; dut.size #= Size.LONG
    // D/A=1 (An), Xn=101(A5), W/L=1(.L), scale=11(*8), brief=0, d8=0xFE(-2)
    dut.words(1) #= ((1<<15)|(5<<12)|(1<<11)|(3<<9)|0xFE); sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.base.toInt == 11)            // A3
    assert(dut.out0.disp.toLong == 0xFFFFFFFEL)  // sext(-2)
    assert(dut.out0.indexReg.toInt == 13)        // A5 = 8+5
    assert(dut.out0.indexLong.toBoolean && dut.out0.indexScale.toInt == 3)
  }}
  test("(d8,PC,Xn) mode 7-3 -> MEMSIMPLE INDEXED pcRel base invalid", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3B /*mode7 reg3*/; dut.size #= Size.LONG
    dut.words(1) #= 0x1206 /* D1, .W, *2, d8=6 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(!dut.out0.baseValid.toBoolean && dut.out0.pcRel.toBoolean)
    assert(dut.out0.disp.toLong == 6)
    assert(dut.out0.indexValid.toBoolean && dut.out0.indexReg.toInt == 1)
  }}
  // ── 68020+ FULL-format extension word (bit8=1) ────────────────────────────────
  // Layout: D/A(15) Xn(14:12) W/L(11) scale(10:9) 1(8) BS(7) IS(6) BD-SIZE(5:4) 0(3) I/IS(2:0)
  // No-memory-indirect (I/IS=000) -> MEMSIMPLE single-pass; mem-indirect -> MEMINDIRECT.
  test("full-format no-mem-indirect (bd null) -> MEMSIMPLE base=A0 index=D1*4", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30 /*mode6 A0*/; dut.size #= Size.LONG
    // D/A=0(Dn) Xn=001(D1) W/L=1(.L) scale=10(*4) full=1 BS=0 IS=0 BD=00 0 I/IS=000
    dut.words(1) #= ((1<<12)|(1<<11)|(2<<9)|(1<<8)|0); sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean && dut.out0.base.toInt == 8)
    assert(dut.out0.disp.toLong == 0)
    assert(dut.out0.indexValid.toBoolean && dut.out0.indexReg.toInt == 1)
    assert(dut.out0.indexLong.toBoolean && dut.out0.indexScale.toInt == 2)
  }}
  test("full-format no-mem-indirect (bd word) -> MEMSIMPLE disp=sext(bd)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    // full=1 BS=0 IS=0 BD=10(word) I/IS=000 ; bd at words(2)
    dut.words(1) #= ((1<<12)|(1<<11)|(2<<9)|(1<<8)|(2<<4)|0)
    dut.words(2) #= 0xFFF0 /* -16 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.disp.toLong == 0xFFFFFFF0L)
    assert(dut.out0.baseValid.toBoolean)
  }}
  test("full-format no-mem-indirect (bd long) -> MEMSIMPLE disp=words(2)##words(3)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    dut.words(1) #= ((1<<8)|(3<<4)|0)   // full, BS=0,IS=0, BD=11(long), I/IS=000, no index(DA=0,Xn=0 but IS=0 -> index valid)
    dut.words(2) #= 0x1234; dut.words(3) #= 0x5678; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.disp.toLong == 0x12345678L)
  }}
  test("full-format BS suppresses base (no-mem-indirect)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    dut.words(1) #= ((1<<8)|(1<<7)|0)   // full, BS=1, IS=0, BD=00, I/IS=000
    sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(!dut.out0.baseValid.toBoolean)
    assert(dut.out0.indexValid.toBoolean)
  }}
  test("full-format IS suppresses index (no-mem-indirect)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    dut.words(1) #= ((1<<8)|(1<<6)|0)   // full, BS=0, IS=1, BD=00, I/IS=000
    sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean)
    assert(!dut.out0.indexValid.toBoolean)
  }}
  test("full-format pre-index (od word) -> MEMINDIRECT memPost=0 od=sext", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    // full=1 BS=0 IS=0 BD=00 I/IS=010 (pre, word od) ; od at words(2)
    dut.words(1) #= ((1<<8)|0x2)
    dut.words(2) #= 0x0010 /* od=16 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMINDIRECT)
    assert(!dut.out0.memPost.toBoolean)
    assert(dut.out0.od.toLong == 16)
    assert(dut.out0.baseValid.toBoolean && dut.out0.indexValid.toBoolean)
  }}
  test("full-format post-index (od long) -> MEMINDIRECT memPost=1 od=long", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    // full=1 BS=0 IS=0 BD=00 I/IS=111 (post, long od) ; od at words(2)##words(3)
    dut.words(1) #= ((1<<8)|0x7)
    dut.words(2) #= 0x0000; dut.words(3) #= 0x0020; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMINDIRECT)
    assert(dut.out0.memPost.toBoolean)
    assert(dut.out0.od.toLong == 0x20)
  }}
  test("full-format pre-index bd-long + od-long: bd@2,3 od@4,5", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    // full=1 BS=0 IS=0 BD=11(long) I/IS=011 (pre, long od)
    dut.words(1) #= ((1<<8)|(3<<4)|0x3)
    dut.words(2) #= 0x1111; dut.words(3) #= 0x2222   // bd = 0x11112222
    dut.words(4) #= 0x3333; dut.words(5) #= 0x4444   // od = 0x33334444
    sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMINDIRECT)
    assert(dut.out0.disp.toLong == 0x11112222L)
    assert(dut.out0.od.toLong == 0x33334444L)
  }}
  test("full-format mode 7-3 PC-rel no-mem-indirect -> MEMSIMPLE pcRel", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3B; dut.size #= Size.LONG
    dut.words(1) #= ((1<<8)|0)   // full, BS=0, IS=0, BD=00, I/IS=000
    sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(!dut.out0.baseValid.toBoolean && dut.out0.pcRel.toBoolean)
  }}
  test("full-format mode 7-3 BS suppresses PC base (no pc fold)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3B; dut.size #= Size.LONG
    dut.words(1) #= ((1<<8)|(1<<7)|0)   // full, BS=1 -> pcRel False
    sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(!dut.out0.pcRel.toBoolean)
  }}
}
