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
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7000,0x4880,0x7001,0x7002,0x7003,0x7004)) // MOVEQ, EXT.W(complex - not yet implemented), ... (was 0xC0C1 MULU, now simple after the MUL slice)
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
}
