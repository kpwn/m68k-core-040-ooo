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
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa)) }
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
      dut.fa.logic.feed.ready #= false; dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      redirect(dut, cd, base)
      dut.fa.logic.feed.ready #= true
      cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean)
      assert(dut.fa.logic.feed.payload(0).pc.toLong == base, s"slot0 pc=${dut.fa.logic.feed.payload(0).pc.toLong.toHexString}")
      assert(dut.fa.logic.feed.payload(0).lenWords.toInt == 1 && !dut.fa.logic.feed.payload(0).complex.toBoolean)
      assert(dut.fa.logic.slot1Valid.toBoolean && dut.fa.logic.feed.payload(1).pc.toLong == base + 2)
    }
  }

  test("complex instruction -> 1-wide complex packet, stall until resume", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x7000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7000,0xC0C1,0x7001,0x7002,0x7003,0x7004)) // MOVEQ, MULU(complex), ...
      dut.fa.logic.feed.ready #= false; dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      redirect(dut, cd, base)
      dut.fa.logic.feed.ready #= true
      var sawComplex = false; var guard = 0
      while (!sawComplex && guard < 80) {
        cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean || {guard += 1; guard >= 80})
        if (dut.fa.logic.feed.valid.toBoolean && dut.fa.logic.feed.payload(0).complex.toBoolean) {
          assert(dut.fa.logic.feed.payload(0).pc.toLong == base + 2, s"complex pc=${dut.fa.logic.feed.payload(0).pc.toLong.toHexString}")
          sawComplex = true
        } else cd.waitSampling()
      }
      assert(sawComplex, "expected a complex packet at base+2")
      cd.waitSampling(3)
      // resume past the 1-word MULU -> next pc = base+4
      dut.fa.logic.resume.valid #= true; dut.fa.logic.resume.payload #= base + 4
      cd.waitSampling(); dut.fa.logic.resume.valid #= false
      cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean)
      assert(dut.fa.logic.feed.payload(0).pc.toLong == base + 4, s"after resume pc=${dut.fa.logic.feed.payload(0).pc.toLong.toHexString}")
    }
  }
}
