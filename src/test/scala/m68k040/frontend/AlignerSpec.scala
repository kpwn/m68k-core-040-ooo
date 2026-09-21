package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class AlignerSpec extends AnyFunSuite {
  class Dut extends Component {
    val headPc    = in UInt(32 bits)
    val words     = in Vec(Bits(16 bits), Aligner.WINDOW)
    val preds     = in Vec(ChunkPredecode(), Aligner.WINDOW)
    val avail     = in UInt(4 bits)
    // FMax closure slice 1 (2026-08-07): the live head re-classify is no longer computed
    // inside `Aligner.align`; it is registered in FetchAlignPlugin and passed in.
    val p0LiveReg = in(ChunkPredecode())
    val res       = out(Aligner.Result())
    res := Aligner.align(headPc, words, preds, avail, p0LiveReg,
      headPcHiP1 = Some(headPc(31 downto 5) + 1))
  }
  // `setAll` also drives every ChunkPredecode field (incl. the new `ambiguousLine`, which
  // must default False so the existing tests exercise the plain `preds(0)` path) and puts
  // `p0LiveReg` in its "not resolved yet" reset shape — every `in` port must be driven or
  // SpinalSim reads X/garbage.
  def setAll(dut: Dut, simple: Boolean, len: Int): Unit = {
    for (i <- 0 until Aligner.WINDOW) {
      dut.words(i) #= 0
      dut.preds(i).simple #= simple; dut.preds(i).lenWords #= len; dut.preds(i).ambiguousLine #= false
    }
    setP0Live(dut, simple = false, len = 0, ambiguous = true)
  }
  def setPred(dut: Dut, i: Int, simple: Boolean, len: Int, ambiguous: Boolean = false): Unit = {
    dut.preds(i).simple #= simple; dut.preds(i).lenWords #= len; dut.preds(i).ambiguousLine #= ambiguous
  }
  def setP0Live(dut: Dut, simple: Boolean, len: Int, ambiguous: Boolean): Unit = {
    dut.p0LiveReg.simple #= simple; dut.p0LiveReg.lenWords #= len; dut.p0LiveReg.ambiguousLine #= ambiguous
  }

  test("slot1 PC preserves low-field carry and 32-bit wrap for baked and resolved lengths", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val bases = Seq(0L, 0x20L, 0x7fffffe0L, 0x80000000L, 0xffffffe0L)
      for (base <- bases; low <- 0 until 32; len <- 1 until Aligner.WINDOW;
           resolved <- Seq(false, true)) {
        val pc = base + low
        dut.headPc #= pc
        setAll(dut, simple = true, len = 1)
        setPred(dut, 0, simple = true, len = if (resolved) 1 else len,
          ambiguous = resolved)
        setP0Live(dut, simple = true, len = len, ambiguous = false)
        dut.avail #= Aligner.WINDOW
        sleep(1)
        assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean,
          s"missing packet pc=$pc len=$len resolved=$resolved")
        assert(dut.res.slot0.pc.toLong == pc)
        assert(dut.res.slot1.pc.toLong == ((pc + 2L * len) & 0xffffffffL),
          s"slot1 PC pc=$pc len=$len resolved=$resolved")
        assert(dut.res.shiftWords.toInt == len + 1)
      }
    }
  }

  test("two adjacent 1-word simple ops -> 2-wide", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x1000; setAll(dut, simple=true, len=1); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 1 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.slot1.pc.toLong == 0x1002)
      assert(dut.res.shiftWords.toInt == 2)
      assert(!dut.res.stall.toBoolean && !dut.res.complex.toBoolean)
    }
  }
  test("3-word simple then 1-word simple -> 2-wide, slot1 pc=+6, shift=4", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x2000; setAll(dut, simple=true, len=1); setPred(dut, 0, simple=true, len=3); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 3)
      assert(dut.res.slot1.pc.toLong == 0x2006 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.shiftWords.toInt == 4)
    }
  }
  test("simple head + complex second -> 1-wide, shift=L0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x3000; setAll(dut, simple=true, len=1); setPred(dut, 1, simple=false, len=0); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && !dut.res.slot1Valid.toBoolean)
      assert(dut.res.shiftWords.toInt == 1 && !dut.res.complex.toBoolean)
    }
  }
  test("complex head -> 1-wide complex packet, stall, shift=0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x4000; setAll(dut, simple=true, len=1); setPred(dut, 0, simple=false, len=0); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot0.complex.toBoolean)
      assert(!dut.res.slot1Valid.toBoolean && dut.res.complex.toBoolean && dut.res.stall.toBoolean)
      assert(dut.res.shiftWords.toInt == 0)
    }
  }
  test("insufficient bytes for head -> stall, slot0 invalid", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x5000; setAll(dut, simple=true, len=3); dut.avail #= 2; sleep(1)
      assert(!dut.res.slot0Valid.toBoolean && dut.res.stall.toBoolean && dut.res.shiftWords.toInt == 0)
    }
  }

  // ── F3 directed test (deep-audit 2026-07-11) ──────────────────────────────────
  // A legal 7-word "simple" instruction (matching the F3 lock-step scenario: a MOVE
  // with a full mem-indirect source + a (d16,An) destination — src consumes 6 words
  // (opword..words(5)) and the dest's OWN displacement is words(6)) must carry ALL 7
  // words through the aligner intact, not just the first 6. BEFORE the fix,
  // `DecodePacket.words` was `Vec(Bits(16 bits), 6)` — index 6 didn't even EXIST (a
  // compile-time-impossible access, not just a runtime truncation), and the aligner's
  // copy loop was hardcoded `for (i <- 0 until 6)`, so word 6 (here: the dest
  // displacement) was UNCONDITIONALLY LOST regardless of `lenWords`. AFTER the fix,
  // `words` holds WINDOW(10) entries and the loop copies all of them, so the real
  // word 6 value survives. (This is orthogonal to F2's overflow: 7 already fit the OLD
  // 3-bit lenWords field with no wraparound — F3 is purely about the packet's own
  // capacity, not the length field's width, which the ExecuteLockStepSpec F3 lock-step
  // test alone cannot isolate since the only known-reachable 7-word MOVE combination
  // routes through a separately-broken µcode entry that never even reads word 6.)
  test("F3 FIX: word index 6 (7th word) of a 7-word simple instruction survives to slot0 " +
       "(was structurally unrepresentable in the old 6-wide DecodePacket.words)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x6000
      setAll(dut, simple = true, len = 1)
      setPred(dut, 0, simple = true, len = 7)
      for (i <- 0 until 10) { dut.words(i) #= (0xA000 + i) }   // distinct per-word marker values
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean, "slot0 must be valid (simple, len=7, avail=10)")
      assert(dut.res.slot0.wordCount.toInt == 7, s"wordCount must be 7, got ${dut.res.slot0.wordCount.toInt}")
      for (i <- 0 until 7) {
        assert(dut.res.slot0.words(i).toInt == (0xA000 + i),
          f"word $i must survive intact: got 0x${dut.res.slot0.words(i).toInt}%04x exp 0x${0xA000 + i}%04x")
      }
    }
  }

  // ── FMax closure slice 1 (2026-08-07): `p0LiveReg` consume semantics ─────────────
  // The live re-classify of an `ambiguousLine` head (task #202) is no longer computed
  // inside `align`; it is REGISTERED in FetchAlignPlugin and passed in. These three
  // tests pin the Mux's contract at the `Aligner` boundary.

  test("p0LiveReg slice-1: ambiguous head NOT yet resolved -> stall (no slot0, shift=0)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x7000
      setAll(dut, simple = true, len = 1)
      // Baked prediction says "simple, len=1" but flags it as a GUESS.
      setPred(dut, 0, simple = true, len = 1, ambiguous = true)
      // The registered live reclassify has not resolved it yet.
      setP0Live(dut, simple = true, len = 1, ambiguous = true)
      dut.avail #= 10   // plenty of words: the ONLY reason to stall is the ambiguity
      sleep(1)
      assert(!dut.res.slot0Valid.toBoolean, "must not emit an unresolved ambiguous head")
      assert(!dut.res.slot1Valid.toBoolean, "slot1 must not be emitted either")
      assert(dut.res.stall.toBoolean && dut.res.shiftWords.toInt == 0,
        "must stall with shift=0 so the IBuf head cannot advance while unresolved")
      assert(!dut.res.complex.toBoolean, "an unresolved ambiguous head is not a complex packet")
    }
  }

  test("p0LiveReg slice-1: ambiguous head RESOLVED -> slot0 uses p0LiveReg, not preds(0)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x7100
      setAll(dut, simple = true, len = 1)
      // Baked (GUESSED, deliberately WRONG) prediction: "brief format, 1 word".
      setPred(dut, 0, simple = true, len = 1, ambiguous = true)
      // Registered live reclassify RESOLVED it: it is really a 3-word full-format EA
      // (this is the real pea_memind shape: 0x4874 / 0x0164 / 0x0020).
      setP0Live(dut, simple = true, len = 3, ambiguous = false)
      for (i <- 0 until Aligner.WINDOW) dut.words(i) #= (0xB000 + i)
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean, "resolved ambiguous head must emit")
      assert(dut.res.slot0.lenWords.toInt == 3,
        s"must take p0LiveReg's len (3), not preds(0)'s guess (1): got ${dut.res.slot0.lenWords.toInt}")
      assert(dut.res.slot0.wordCount.toInt == 3, s"wordCount=${dut.res.slot0.wordCount.toInt}")
      assert(!dut.res.slot0.complex.toBoolean && dut.res.slot0.simple.toBoolean,
        "p0LiveReg.simple=true must select the simple-packet arm")
      // slot1 starts at head+L0 words, i.e. preds(3) (len=1 from setAll) -> pc = +6, shift = 4.
      assert(dut.res.slot1Valid.toBoolean && dut.res.slot1.pc.toLong == 0x7106,
        s"slot1 pc=${dut.res.slot1.pc.toLong.toHexString} (slot1 must be framed off p0LiveReg's L0)")
      assert(dut.res.shiftWords.toInt == 4, s"shift=${dut.res.shiftWords.toInt}")
    }
  }

  test("p0LiveReg slice-1: NON-ambiguous head IGNORES p0LiveReg entirely", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x7200
      setAll(dut, simple = true, len = 1)
      // Head's baked prediction is trustworthy: 3 words, NOT a guess.
      setPred(dut, 0, simple = true, len = 3, ambiguous = false)
      // p0LiveReg holds a stale/unrelated value that would produce a visibly different
      // (wrong) answer if the Mux ever consumed it in the non-ambiguous case: it claims
      // COMPLEX + len 7, which would route to the complex arm instead of a 3-word simple
      // packet.
      setP0Live(dut, simple = false, len = 7, ambiguous = false)
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot0.simple.toBoolean && !dut.res.complex.toBoolean,
        "non-ambiguous head must use preds(0) (simple), never p0LiveReg (complex)")
      assert(dut.res.slot0.lenWords.toInt == 3,
        s"must take preds(0)'s len (3), not p0LiveReg's (7): got ${dut.res.slot0.lenWords.toInt}")
      assert(dut.res.shiftWords.toInt == 4, s"shift=${dut.res.shiftWords.toInt}")
    }
  }
}
