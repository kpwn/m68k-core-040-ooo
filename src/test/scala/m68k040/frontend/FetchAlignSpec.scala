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
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7000,0x48E7,0x7001,0x7002,0x7003,0x7004)) // MOVEQ, MOVEM(complex - not yet implemented; was 0x4880 EXT.W, now simple after the line-4 unary slice)
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
}
