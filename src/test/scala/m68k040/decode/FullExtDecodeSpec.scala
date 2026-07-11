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

  // ── F5 (deep-audit 2026-07-11): extW validity gating ──────────────────────────
  // Two DUTs wired to the SAME (op, extW) inputs, differing only in whether extW is
  // declared VALID (real data) or NOT (e.g. the word was past the predecoded I-cache
  // line's end and IcachePlugin zero-filled it, per the classify() 5-arg overload's
  // `extWValid` param). extW2 is unused by these mode-6 single-EA opcodes -> always valid.
  // `extWValid` is a Scala-level Boolean in the RTL API (elaboration-time only — plumbed
  // through as a plain Boolean, not a hardware Bool, since "is word i+k past the line end"
  // is a compile-time constant at IcachePlugin's real call site), so it can't be a runtime
  // `in Bool` port — hence two separate DUT classes (True baked in vs False baked in)
  // rather than one DUT with a poke-able validity port.
  class PdWordKnownDut extends Component {
    val op   = in Bits (16 bits)
    val extW = in Bits (16 bits)
    val res  = out(ChunkPredecode())
    res := PredecodeWord.classify(op, extW, B(0, 16 bits), extWValid = true, extW2Valid = true)
  }
  class PdWordUnknownDut extends Component {
    val op   = in Bits (16 bits)
    val extW = in Bits (16 bits)
    val res  = out(ChunkPredecode())
    res := PredecodeWord.classify(op, extW, B(0, 16 bits), extWValid = false, extW2Valid = true)
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

  // ── F5 directed tests (deep-audit 2026-07-11) ─────────────────────────────────
  // The IcachePlugin PREDECODE loop passes extWValid=(i+1<nWords) — False for the LAST
  // word of a 64-byte line (its ext word would be the FIRST word of the NEXT, not-yet-
  // fetched line). Fail-before/pass-after is expressed here as "known vs unknown" since
  // that's the exact boolean IcachePlugin computes and passes through; the end-to-end
  // I-cache-line-boundary wiring itself is a compile-time-constant computation
  // (`i+1 < nWords`) confirmed by reading IcachePlugin.scala's PREDECODE state, not a
  // runtime behavior that needs a second (redundant) simulation to exercise.
  test("F5: full-format src, extW KNOWN -> correctly framed FULL (not silently brief)", VerilatorTest) {
    SimConfig.withVerilator.compile(new PdWordKnownDut).doSim { dut =>
      // move.l (...,A0,Xn),D2 = 0x2430, FULL-format (bit8=1), word bd -> 1+1=2 EA ext words.
      dut.op #= 0x2430; dut.extW #= fullExt(2, 0x0); sleep(1)
      assert(dut.res.simple.toBoolean, "extW known: full-format src must still frame simple")
      assert(dut.res.lenWords.toInt == 1 + 2, s"extW known: expected len 3, got ${dut.res.lenWords.toInt}")
    }
  }
  test("F5 FIX: full-format src, extW UNKNOWN (line-boundary) -> COMPLEX, not silently brief", VerilatorTest) {
    SimConfig.withVerilator.compile(new PdWordUnknownDut).doSim { dut =>
      // SAME opword+extW bit pattern as the "known" case above (a genuinely full-format
      // src needing 3 words) — but extWValid=False (as IcachePlugin passes when this word
      // sits at the very end of a 64B line). BEFORE the F5 fix, classify() had no
      // `extWValid` concept at all and unconditionally trusted `eaW(8)`, so this would
      // have framed identically to the "known" case above (simple, len=3) REGARDLESS of
      // whether the real ext word (unavailable here) actually had bit8 set — silently
      // WRONG whenever the true (invisible) word differed from the caller's zero-fill.
      // AFTER the fix: eaWKnown=False on a mode-6 EA forces `ok:=False` -> COMPLEX,
      // a safe trap instead of a guess.
      dut.op #= 0x2430; dut.extW #= fullExt(2, 0x0); sleep(1)
      assert(!dut.res.simple.toBoolean,
        "extW unknown (line boundary): must be COMPLEX, not a silent brief/full guess")
    }
  }
  test("F5: a FIXED-length EA mode ((d16,An), mode 5) is UNAFFECTED by extW validity", VerilatorTest) {
    // mode 5 ((d16,An)) is ALWAYS exactly 1 ext word (a plain displacement) — eaExt's
    // mode-5 case never reads eaW's CONTENT at all (unlike mode 6 / mode7-reg3, which must
    // read bit8 just to know whether it's brief-vs-full in the first place, so eaWKnown
    // gates ANY length determination there, not just the "it turned out full" case — see
    // the mode-6 "brief bit pattern" case below, which is ALSO correctly rejected: you
    // can't know it's brief without the real word either). Confirms the F5 fix is scoped
    // to exactly the modes whose length is CONTENT-dependent (6 / mode7-reg3).
    // move.l (4,%a0),%d2 = 0x2428 (MOVE.L dst D2, src mode5 (d16,A0)) -> opword + 1 disp.
    SimConfig.withVerilator.compile(new PdWordUnknownDut).doSim { dut =>
      dut.op #= 0x2428; dut.extW #= 0x1204 /* the disp value; irrelevant to LENGTH */; sleep(1)
      assert(dut.res.simple.toBoolean && dut.res.lenWords.toInt == 2,
        "(d16,An) framing must be unaffected by extW validity (content-independent length)")
    }
  }
  test("F5: extW UNKNOWN also rejects a mode-6 opword whose (inaccessible) real word WOULD be brief", VerilatorTest) {
    // Sharpens the F5 fix's conservatism: even when the caller happens to pass a
    // brief-shaped (bit8=0) placeholder for an unknown/unavailable extW, mode 6 must still
    // reject — the RTL cannot know the placeholder matches the REAL (invisible) word's
    // bit8, so eaWKnown gates the length decision itself (brief-vs-full), not merely the
    // "it turned out full" outcome. (The real IcachePlugin caller always passes a literal
    // 0 placeholder when invalid — B(0,16 bits), i.e. bit8=0/"brief-shaped" — so this is
    // exactly the real code path, not a contrived corner case.)
    SimConfig.withVerilator.compile(new PdWordUnknownDut).doSim { dut =>
      dut.op #= 0x2430; dut.extW #= 0x0000 /* brief-shaped placeholder, bit8=0 */; sleep(1)
      assert(!dut.res.simple.toBoolean,
        "extW unknown: must reject even when the placeholder LOOKS brief")
    }
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
      // bd-long + od-long = 5 EA ext words -> 6-word instr (single-EA src, dst=Dn adds 0);
      // fits comfortably under the 4-bit lenWords field (max 15; widened 2026-07-11, F2)
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
