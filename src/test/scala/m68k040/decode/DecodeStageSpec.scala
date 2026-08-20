package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite
import m68k040.services.FrontendDebugMatchService

class DecodeStageSpec extends AnyFunSuite {
  class DebugMatchDriverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val service = host[FrontendDebugMatchService]
      val pcs = in(Vec(UInt(32 bits), 4))
      val enables = in(Bits(4 bits))
      val skipOnce = in(Bits(4 bits))
      service.configure(pcs, enables, skipOnce)
      val skipConsumed = out(Bits(4 bits))
      skipConsumed := service.skipConsumed
    }
  }
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val dec = new DecodeStage
    val debugMatch = new DebugMatchDriverPlugin
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, debugMatch, sink)) }
  }
  test("frontend decodes a 2-wide MOVEQ stream into MOVE uops", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x8000L
      // MOVEQ #1,D0 = 0x7001; MOVEQ #2,D1 = 0x7202; MOVEQ #3,D2 = 0x7403; MOVEQ #4,D3 = 0x7604
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7001, 0x7202, 0x7403, 0x7604))
      dut.sink.logic.uopsOut.ready #= false
      dut.debugMatch.logic.pcs.foreach(_ #= 0)
      dut.debugMatch.logic.enables #= 0
      dut.debugMatch.logic.skipOnce #= 0
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.sink.logic.uopsOut.ready #= true
      cd.waitSamplingWhere(dut.sink.logic.uopsOut.valid.toBoolean)
      val u0 = dut.sink.logic.uopsOut.payload(0)
      assert(u0.op.toEnum == DecOp.MOVE && u0.dstReg.toInt == 0 && u0.imm.toLong == 1,
        s"u0 op=${u0.op.toEnum} dst=${u0.dstReg.toInt} imm=${u0.imm.toLong}")
      assert(dut.sink.logic.u1v.toBoolean, "slot1 should be valid (2-wide)")
      val u1 = dut.sink.logic.uopsOut.payload(1)
      assert(u1.op.toEnum == DecOp.MOVE && u1.dstReg.toInt == 1 && u1.imm.toLong == 2,
        s"u1 op=${u1.op.toEnum} dst=${u1.dstReg.toInt} imm=${u1.imm.toLong}")
    }
  }

  test("registered frontend PC matcher stamps the selected slot and consumes skip-once", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x9000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7001, 0x7202, 0x7403, 0x7604))
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid #= false
      dut.debugMatch.logic.pcs.foreach(_ #= 0)
      dut.debugMatch.logic.pcs(2) #= base
      dut.debugMatch.logic.enables #= 4
      dut.debugMatch.logic.skipOnce #= 0
      cd.waitSampling(3) // frontend-local registered configuration snapshot
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      cd.waitSamplingWhere(dut.sink.logic.uopsOut.valid.toBoolean)
      assert(dut.sink.logic.uopsOut.payload(0).debugBreakValid.toBoolean)
      assert(dut.sink.logic.uopsOut.payload(0).debugBreakSlot.toInt == 2)

      // A fresh run with skip armed suppresses the marker and reports exactly the slot
      // when the first uop enters the registered push stage.
      dut.debugMatch.logic.skipOnce #= 4
      dut.dec.logic.pipeFlush #= true
      cd.waitSampling(); dut.dec.logic.pipeFlush #= false
      cd.waitSampling(2)
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var consumed = false
      var checked = false
      for (_ <- 0 until 30 if !checked) {
        cd.waitSampling()
        consumed ||= (dut.debugMatch.logic.skipConsumed.toInt & 4) != 0
        if (dut.sink.logic.uopsOut.valid.toBoolean) {
          assert(!dut.sink.logic.uopsOut.payload(0).debugBreakValid.toBoolean)
          assert(dut.sink.logic.uopsOut.payload(0).debugBreakSlot.toInt == 2)
          checked = true
        }
      }
      assert(checked, "skipped breakpoint macro never reached decode output")
      assert(consumed, "skip-once consumption pulse for slot 2 was not observed")
    }
  }
}
