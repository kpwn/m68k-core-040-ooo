package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class FetchAlignSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val probe = new DecodeFeedProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, probe)) }
  }
  def redirect(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= pc
    cd.waitSampling(); dut.fa.logic.redirect.valid #= false
  }

  test("2-wide stream of 1-word simple ops, correct PCs", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x6000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(8)(0x7000)) // MOVEQ x8
      dut.probe.logic.feedOut.ready #= false; dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      cd.waitSamplingWhere(dut.probe.logic.feedOut.valid.toBoolean)
      assert(dut.probe.logic.feedOut.payload(0).pc.toLong == base, s"slot0 pc=${dut.probe.logic.feedOut.payload(0).pc.toLong.toHexString}")
      assert(dut.probe.logic.feedOut.payload(0).lenWords.toInt == 1 && !dut.probe.logic.feedOut.payload(0).complex.toBoolean)
      assert(dut.probe.logic.s1v.toBoolean && dut.probe.logic.feedOut.payload(1).pc.toLong == base + 2)
    }
  }

  test("complex instruction -> 1-wide complex packet, stall until resume", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x7000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7000,0xF000,0x7001,0x7002,0x7003,0x7004)) // MOVEQ, then a line-F coprocessor opword (still COMPLEX/unframed; 0x48E7 used to stand in here but is now a real MOVEM.L <list>,-(A7))
      dut.probe.logic.feedOut.ready #= false; dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      var sawComplex = false; var guard = 0
      while (!sawComplex && guard < 80) {
        cd.waitSampling(); guard += 1
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.payload(0).complex.toBoolean) {
          assert(dut.probe.logic.feedOut.payload(0).pc.toLong == base + 2, s"complex pc=${dut.probe.logic.feedOut.payload(0).pc.toLong.toHexString}")
          sawComplex = true
        }
      }
      assert(sawComplex, "expected a complex packet at base+2")
      cd.waitSampling(3)
      // resume past the 1-word MULU -> next pc = base+4
      dut.fa.logic.resume.valid #= true; dut.fa.logic.resume.payload #= base + 4
      cd.waitSampling(); dut.fa.logic.resume.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.feedOut.valid.toBoolean)
      assert(dut.probe.logic.feedOut.payload(0).pc.toLong == base + 4, s"after resume pc=${dut.probe.logic.feedOut.payload(0).pc.toLong.toHexString}")
    }
  }

  test("redirect during an in-flight fetch discards the stale window, fetches the new target", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      // Two distinct windows in ONE memory image (a single AXI slave agent; two agents
      // on the same bus would both respond and wedge the handshake). A = MOVEQ #1 x8,
      // B = MOVEQ #2 x8, B placed in the SAME image right after A (different 8-byte windows).
      val a = 0x8000L; val b = 0x8010L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, a,
        Seq.fill(8)(0x7201) ++ Seq.fill(8)(0x7402)) // A: MOVEQ #1,%d1 ; B: MOVEQ #2,%d2
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      // Redirect to A (cold -> miss/refill in flight), then quickly redirect to B before
      // A's window can be consumed — B must win, with a correct (lenWords>0) packet at B.
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= a
      cd.waitSampling(); dut.fa.logic.redirect.payload #= b
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.probe.logic.feedOut.ready #= true
      cd.waitSamplingWhere(dut.probe.logic.feedOut.valid.toBoolean)
      assert(dut.probe.logic.feedOut.payload(0).pc.toLong == b,
        s"expected target B=0x${b.toHexString}, got 0x${dut.probe.logic.feedOut.payload(0).pc.toLong.toHexString}")
      assert(dut.probe.logic.feedOut.payload(0).lenWords.toInt > 0,
        s"stale-window leak: lenWords=${dut.probe.logic.feedOut.payload(0).lenWords.toInt} (would self-redirect)")
    }
  }

  // ── FMax closure slice 1 (2026-08-07): registered `p0LiveReg` ──────────────────────
  //
  // Memory image shared by the two tests below. Every word defaults to MOVEQ #0,%d0
  // (0x7000 — 1 word, `simple`, never `ambiguousLine`), with two deliberately
  // AMBIGUOUS instructions planted at the LAST word (index 31) of two different 64-byte
  // I-cache lines, which is exactly the condition IcachePlugin's refill-time predecode
  // cannot resolve (`extWValid = i+1 < 32` is False there -> the mode-6 "assume brief"
  // guess + `ambiguousLine=True`; see PredecodeWord.eaExt / ChunkPredecode's doc comment).
  //
  // The two encodings are NOT invented — they are the real full-format memory-indirect
  // PEA shape from `src/test/resources/m68kooo-ported-tests/asm/pea_memind.s`, which
  // spells the derivation out in its own comments:
  //
  //   opword 0x4874 = PEA <ea> (0100 1000 01 mmmrrr) with mmm=110 (d8,An,Xn) rrr=100 (A4)
  //                   — mode 6 is precisely the brief-vs-full-format EA whose framing
  //                   needs the EXTENSION word's bit 8, i.e. the ambiguous case.
  //   A: ext 0x0164  = pea_memind.s's own "PEA ([32,A4])" first extension word
  //                    (bit8=1 => FULL format; bits5:4=10 => bd.W = 1 word; bit1=0 => no
  //                    outer disp) -> PredecodeWord.fullExtLen = 1+1+0 = 2
  //                    => TRUE lenWords = 1 + 2 = 3   (opword, 0x0164, bd.W 0x0020)
  //   B: ext 0x0174  = the same shape with bits5:4=11 => bd.L = 2 words
  //                    -> fullExtLen = 1+2+0 = 3
  //                    => TRUE lenWords = 1 + 3 = 4   (opword, 0x0174, bd.L 0x0000,0x0000)
  //
  // The refill-time GUESS for both is "brief" => lenWords = 2. So each of 3 and 4 is
  // distinguishable both from the guess AND from the other — which is what makes a
  // stale/leaked `p0LiveReg` observable rather than coincidentally right.
  private val p0LiveBase   = 0x9000L      // 64-byte aligned
  private val p0LiveAPc    = 0x903EL      // word 31 of line 0x9000  (true lenWords = 3)
  private val p0LiveBPc    = 0x90BEL      // word 31 of line 0x9080  (true lenWords = 4)
  private def p0LiveImage(): Seq[Int] = {
    val img = Array.fill(160)(0x7000)                       // MOVEQ #0,%d0 filler
    img(31) = 0x4874; img(32) = 0x0164; img(33) = 0x0020    // A @ 0x903E, ext words in line 0x9040
    img(95) = 0x4874; img(96) = 0x0174; img(97) = 0x0000; img(98) = 0x0000  // B @ 0x90BE
    img.toSeq
  }
  /** Wait (bounded) for a fired/valid feed packet at `pc`; returns its lenWords. */
  private def awaitPacketAt(dut: Dut, cd: ClockDomain, pc: Long, maxCycles: Int = 400): Int = {
    var guard = 0
    while (guard < maxCycles) {
      if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.payload(0).pc.toLong == pc)
        return dut.probe.logic.feedOut.payload(0).lenWords.toInt
      cd.waitSampling(); guard += 1
    }
    fail(s"no decode packet at pc=0x${pc.toHexString} within $maxCycles cycles")
  }

  // Regression guard for the head-window-change invalidation of `p0LiveReg`.
  // The aligner emits the 1-word MOVEQ at 0x903C and SHIFTS; the next cycle's head is the
  // AMBIGUOUS PEA at 0x903E. A naively-registered `p0Live` would, on that very cycle, still
  // hold the PREVIOUS head's fully-RESOLVED classify (the MOVEQ: simple, lenWords=1,
  // ambiguousLine=False) — and `Aligner`'s Mux, seeing preds(0).ambiguousLine=True, would
  // consume it as the PEA's length, silently framing the 3-word PEA as 1 word and leaving
  // its two extension words behind as a bogus stray instruction (the exact task #209
  // wild-PC failure mode, one slot over). FetchAlignPlugin's `p0LiveInvalidate` (which
  // includes `shift =/= 0`) is what prevents that; this test fails without it.
  test("p0LiveReg slice-1: shifting INTO an ambiguous head must not consume the previous head's resolved classify", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, p0LiveBase, p0LiveImage())
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      // Start at the 1-word MOVEQ immediately BEFORE the ambiguous PEA (word 30 of the line).
      redirect(dut, cd, p0LiveAPc - 2)
      dut.probe.logic.feedOut.ready #= true
      val movqLen = awaitPacketAt(dut, cd, p0LiveAPc - 2)
      assert(movqLen == 1, s"MOVEQ at 0x${(p0LiveAPc - 2).toHexString} lenWords=$movqLen (expected 1)")
      cd.waitSampling()   // the MOVEQ fires -> shift=1 -> the ambiguous PEA becomes the head
      val peaLen = awaitPacketAt(dut, cd, p0LiveAPc)
      assert(peaLen == 3,
        s"ambiguous PEA at 0x${p0LiveAPc.toHexString} framed as $peaLen words (expected 3; " +
        "1 == leaked the previous head's registered classify, 2 == trusted the refill-time guess)")
    }
  }

  // The plan's required flush-mid-resolve test: a redirect arriving while `p0LiveReg`
  // holds a RESOLVED value for the head it was about to emit must not let that value
  // reach a coincidentally-also-ambiguous head at the NEW target. Holding feedOut.ready
  // low parks the front-end in exactly that state (aligner resolved, packet presented,
  // never consumed, so no shift ever retires it) — then the redirect lands.
  test("p0LiveReg slice-1: a redirect landing on a RESOLVED ambiguous head does not leak into the new target's decode", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, p0LiveBase, p0LiveImage())
      dut.probe.logic.feedOut.ready #= false   // held low for the WHOLE first phase
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      redirect(dut, cd, p0LiveAPc)
      // Phase 1: the ambiguous head resolves (this alone proves the registered
      // re-classify path still works end-to-end), but is never consumed (ready=false),
      // so `p0LiveReg` sits parked holding A's RESOLVED 3-word classify.
      val aLen = awaitPacketAt(dut, cd, p0LiveAPc)
      assert(aLen == 3, s"ambiguous PEA A at 0x${p0LiveAPc.toHexString} framed as $aLen words (expected 3)")
      cd.waitSampling(4)   // let it sit parked/resolved for a few cycles
      assert(dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.payload(0).pc.toLong == p0LiveAPc,
        "phase 1 setup: the resolved packet must still be parked (never fired) when the redirect lands")
      // Phase 2: flush mid-resolve, to a DIFFERENT also-ambiguous instruction whose TRUE
      // length (4) differs from A's (3) and from the refill-time guess (2). Any leak of
      // A's parked resolved value would frame B as 3 words.
      redirect(dut, cd, p0LiveBPc)
      dut.probe.logic.feedOut.ready #= true
      val bLen = awaitPacketAt(dut, cd, p0LiveBPc)
      assert(bLen == 4,
        s"ambiguous PEA B at 0x${p0LiveBPc.toHexString} framed as $bLen words (expected 4; " +
        "3 == leaked the pre-flush target's resolved p0LiveReg, 2 == trusted the refill-time guess)")
    }
  }
}
