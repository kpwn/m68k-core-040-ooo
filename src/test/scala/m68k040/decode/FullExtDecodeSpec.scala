package m68k040.decode

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import m68k040.frontend.PredecodeWord
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** 68020+ FULL-format extension-word decode: predecode variable-length framing (the
  * top correctness hazard) + EaDecoder full-format field extraction + MEMSIMPLE vs
  * MEMINDIRECT classification. (Lock-step vs Musashi lives in ExecuteLockStepSpec.) */
class FullExtDecodeSpec extends AnyFunSuite {

  // ── Predecode length framing (2-arg classify with the EA ext word) ───────────
  class PdDut extends Component {
    val op   = in Bits (16 bits)
    val extW = in Bits (16 bits)
    val res  = out(ChunkPredecode())
    res := PredecodeWord.classify(op, extW)
  }
  def pd(f: PdDut => Unit): Unit = SimConfig.withVerilator.compile(new PdDut).doSim { dut =>
    dut.op #= 0; dut.extW #= 0; f(dut)
  }

  // full ext word builder: bit8=1, BS/IS, bd-size (5:4), I/IS (2:0), od bits already in I/IS lo.
  def fullExt(bdSize: Int, iis: Int, bs: Int = 0, is_ : Int = 0,
              da: Int = 0, xn: Int = 1, wl: Int = 1, scale: Int = 0): Int =
    (da << 15) | (xn << 12) | (wl << 11) | (scale << 9) | (1 << 8) | (bs << 7) |
    (is_ << 6) | (bdSize << 4) | iis

  // expected EA-ext word count for a full-format EA = 1 + bdWords + odWords.
  def expEaExt(bdSize: Int, iis: Int): Int = {
    val bdW = bdSize match { case 2 => 1; case 3 => 2; case _ => 0 }
    // od present iff bit1 of iis; long iff bit0. (For I/IS=000 no-mem-indirect, iis=0 -> no od.)
    val odW = if ((iis & 0x2) != 0) (if ((iis & 0x1) != 0) 2 else 1) else 0
    1 + bdW + odW
  }

  test("predecode brief indexed src still frames len 2 (byte-identical)", VerilatorTest) { pd { dut =>
    // move.l (4,%a0,%d1.w*2),%d2 = 0x2430, brief ext (bit8=0)
    dut.op #= 0x2430; dut.extW #= 0x1204; sleep(1)
    assert(dut.res.simple.toBoolean && dut.res.lenWords.toInt == 2)
  }}

  test("predecode MOVE src full-format: len for every bd/od combo (no-mem-indir + pre/post)", VerilatorTest) { pd { dut =>
    val opMoveSrc = 0x2430   // MOVE.L (...,A0,Xn),D2  src mode6 reg0
    // no-mem-indirect (iis=000): bd null/word/long -> EA ext 1/2/3
    for (bd <- Seq(0, 2, 3)) {
      val ext = fullExt(bd, 0x0)
      dut.op #= opMoveSrc; dut.extW #= ext; sleep(1)
      val exp = 1 + expEaExt(bd, 0x0)   // opword + EA ext
      assert(dut.res.simple.toBoolean, f"bd=$bd iis=000 expected simple")
      assert(dut.res.lenWords.toInt == exp, f"bd=$bd iis=000 len=${dut.res.lenWords.toInt} exp=$exp")
    }
    // pre-index (iis in 001/010/011) + post-index (101/110/111): od null/word/long
    for (iis <- Seq(0x1, 0x2, 0x3, 0x5, 0x6, 0x7); bd <- Seq(0, 2, 3)) {
      // bd-long + od-long = 5 EA ext words -> 6-word instr; lenWords field is 3 bits (max 7) ok
      val ext = fullExt(bd, iis)
      dut.op #= opMoveSrc; dut.extW #= ext; sleep(1)
      val exp = 1 + expEaExt(bd, iis)
      assert(dut.res.simple.toBoolean, f"bd=$bd iis=$iis expected simple")
      assert(dut.res.lenWords.toInt == exp, f"bd=$bd iis=$iis len=${dut.res.lenWords.toInt} exp=$exp")
    }
  }}

  test("predecode ALU src full-format frames length", VerilatorTest) { pd { dut =>
    // add.l (...,A0,Xn),D2 = 0xD4B0 (line D, dst D2, opmode2 .L, src mode6 reg0)
    dut.op #= 0xD4B0; dut.extW #= fullExt(2, 0x2); sleep(1)   // bd word + pre word-od -> 1+1+1=3 EA ext
    assert(dut.res.simple.toBoolean && dut.res.lenWords.toInt == 1 + 3)
  }}

  test("predecode LEA full-format control EA frames length", VerilatorTest) { pd { dut =>
    // lea (...,A0,Xn),A1 = 0x43F0 (LEA A1, mode6 reg0)
    dut.op #= 0x43F0; dut.extW #= fullExt(3, 0x0); sleep(1)   // bd long, no-mem-indir -> 1+2=3 EA ext
    assert(dut.res.simple.toBoolean && dut.res.lenWords.toInt == 1 + 3)
  }}

  test("predecode brief framing preserved when extW has bit8=0", VerilatorTest) { pd { dut =>
    dut.op #= 0x2430; dut.extW #= 0x0004 /* bit8=0 brief */; sleep(1)
    assert(dut.res.simple.toBoolean && dut.res.lenWords.toInt == 2)
  }}

  // ── EaDecoder classification + field extraction ──────────────────────────────
  class EaDut extends Component {
    val eaField = in Bits (6 bits)
    val size    = in(Size())
    val words   = in(Vec(Bits(16 bits), 6))
    val out0    = out(EaSpec())
    out0 := EaDecoder.decode(eaField, size, words)
  }
  def ea(f: EaDut => Unit): Unit = SimConfig.withVerilator.compile(new EaDut).doSim { dut =>
    dut.words.foreach(_ #= 0); dut.size #= Size.LONG; f(dut)
  }

  test("EaDecoder no-mem-indirect classifies MEMSIMPLE", VerilatorTest) { ea { dut =>
    dut.eaField #= 0x30; dut.words(1) #= fullExt(0, 0x0); sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
  }}
  test("EaDecoder pre-index classifies MEMINDIRECT memPost=0", VerilatorTest) { ea { dut =>
    dut.eaField #= 0x30; dut.words(1) #= fullExt(0, 0x2); dut.words(2) #= 0x0008; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMINDIRECT && !dut.out0.memPost.toBoolean)
    assert(dut.out0.od.toLong == 8)
  }}
  test("EaDecoder post-index classifies MEMINDIRECT memPost=1", VerilatorTest) { ea { dut =>
    dut.eaField #= 0x30; dut.words(1) #= fullExt(0, 0x6); dut.words(2) #= 0x0008; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMINDIRECT && dut.out0.memPost.toBoolean)
  }}
  test("EaDecoder reserved bd-size 01 -> null (0 words, disp 0)", VerilatorTest) { ea { dut =>
    dut.eaField #= 0x30; dut.words(1) #= fullExt(1, 0x0); dut.words(2) #= 0xDEAD; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE && dut.out0.disp.toLong == 0)
  }}
}
