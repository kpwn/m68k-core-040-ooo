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
import m68k040.services.{FrontendDebugMatchService, GshareSecondaryLookupService, DecodeFeedService, DecodeUopService, BranchPredRec}

class DecodeStageSpec extends AnyFunSuite {
  class TrainingIndexPlugin(predictTaken: Boolean = false) extends FiberPlugin with GshareSecondaryLookupService {
    val logic = during build new Area {
      val index = Reg(UInt(11 bits)) init 0x155
      index := index + 1
      val taken = Bool(); taken := Bool(predictTaken)
    }
    override def secondaryPhtIndex: UInt = logic.index
    override def secondaryPhtTaken: Bool = logic.taken
  }
  class PredictionProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val df = host[DecodeFeedService]
      val du = host[DecodeUopService]
      val feedFire = out Bool(); feedFire := df.feed.fire
      val second = out Bool(); second := df.slot1Valid
      val pc = out(Vec(UInt(32 bits), 2))
      val tag = out(Vec(UInt(m68k040.Global.BR_PRED_TAG_W bits), 2))
      val valid = out(Vec(Bool(), 2))
      val index = out(Vec(UInt(11 bits), 2))
      for (i <- 0 until 2) {
        pc(i) := df.feed.payload(i).pc; tag(i) := df.feed.payload(i).brPredTag
        valid(i) := df.feed.payload(i).phtValid; index(i) := df.feed.payload(i).phtIndex
      }
      val expanded = out(Vec(BranchPredRec(), 2)); expanded := du.uopPred
    }
  }
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
  class Dut(trainSlot1: Boolean = false, deferTaken: Boolean = false,
            predictTaken: Boolean = false) extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val index = new TrainingIndexPlugin(predictTaken)
    val fa = new FetchAlignPlugin(trainSlot1Conditional = trainSlot1,
      deferTakenSlot1Conditional = deferTaken)
    val dec = new DecodeStage(allowSlot1Prediction = trainSlot1)
    val predictions = new PredictionProbePlugin
    val debugMatch = new DebugMatchDriverPlugin
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, index, fa, dec, predictions, debugMatch, sink)) }
  }

  test("slot-1 training records survive backpressure and prediction-tag wrap", VerilatorTest) {
    for ((enabled, selective, taken) <- Seq((false, false, false), (true, false, false),
      (true, true, false), (true, true, true))) {
      SimConfig.withVerilator.compile(new Dut(enabled, selective, taken)).doSim(s"slot1_${enabled}_${selective}_$taken", 1) { dut =>
        val cd = dut.clockDomain; cd.forkStimulus(10)
        val base = 0x8000L
        val pairs = 80
        IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base,
          Seq.fill(pairs)(Seq(0x7001, 0x6702)).flatten ++ Seq.fill(16)(0x7000))
        dut.debugMatch.logic.pcs.foreach(_ #= 0)
        dut.debugMatch.logic.enables #= 0; dut.debugMatch.logic.skipOnce #= 0
        dut.sink.logic.uopsOut.ready #= false
        dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
        cd.waitSampling(2)
        dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
        cd.waitSampling(); dut.fa.logic.redirect.valid #= false
        val expected = scala.collection.mutable.Queue.empty[(Long, Int, Boolean, Int)]
        val tags = scala.collection.mutable.ArrayBuffer.empty[Int]
        var retiredFromDecode = 0
        var cycles = 0
        while (retiredFromDecode < pairs * 2 && cycles < 3000) {
          dut.sink.logic.uopsOut.ready #= (cycles % 7 >= 2)
          cd.waitSampling(); cycles += 1
          val p = dut.predictions.logic
          if (p.feedFire.toBoolean) {
            for (i <- 0 until (if (p.second.toBoolean) 2 else 1)) {
              val pc = p.pc(i).toLong
              expected.enqueue((pc, p.tag(i).toInt, p.valid(i).toBoolean, p.index(i).toInt))
              if (pc < base + pairs * 4 && ((pc - base) % 4 == 2)) {
                val deferred = selective && taken
                assert(i == (if (deferred) 0 else 1), "wrong conditional admission lane")
                // This fixture has no BTB, so a deferred cold slot-0 branch
                // stays untagged. The full-core profile covers trained targets.
                assert(p.valid(i).toBoolean == (enabled && !deferred))
                if (enabled && !deferred) { assert(p.tag(i).toInt != 0); tags += p.tag(i).toInt }
              }
            }
          }
          val out = dut.sink.logic.uopsOut
          if (out.valid.toBoolean && out.ready.toBoolean) {
            for (i <- 0 until (if (dut.sink.logic.u1v.toBoolean) 2 else 1)) {
              val (pc, tag, valid, index) = expected.dequeue()
              assert(out.payload(i).pc.toLong == pc)
              assert(out.payload(i).brPredTag.toInt == tag)
              assert(p.expanded(i).phtValid.toBoolean == valid)
              assert(!p.expanded(i).predTaken.toBoolean)
              if (valid) assert(p.expanded(i).phtIndex.toInt == index,
                s"prediction index moved to another packet at pc=$pc")
              // A final branch in slot0 may co-emit a guard MOVEQ. Count only
              // the fixture's 160 instructions, not the whole boundary packet.
              if (pc < base + pairs * 4) retiredFromDecode += 1
            }
          }
        }
        assert(retiredFromDecode == pairs * 2)
        if (enabled && !(selective && taken)) {
          assert(tags.size == pairs)
          val capacity = (1 << m68k040.Global.BR_PRED_TAG_W) - 1
          assert(tags.toSeq == (0 until pairs).map(i => (i % capacity) + 1))
        }
      }
    }
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
      // Sample for the FULL window, not `if !checked`. This loop used to stop the moment
      // uopsOut went valid, but `skipConsumed` can pulse on or AFTER that same cycle -- so
      // the test raced its own two observations and lost a run in ten to
      // "skip-once consumption pulse for slot 2 was not observed". They are independent
      // events: watch both across the whole window, and let `checked` gate only the payload
      // assertions.
      // 30 cycles was too tight: after a pipeFlush + redirect the macro sometimes needs
      // longer to reach the output, losing a run in ten to "skipped breakpoint macro
      // never reached decode output". The FIRST half of this test waits UNBOUNDED via
      // waitSamplingWhere; only this half guessed a bound. Give it a generous one and
      // exit as soon as BOTH events are seen, so the common case stays fast.
      for (_ <- 0 until 200 if !(checked && consumed)) {
        cd.waitSampling()
        consumed ||= (dut.debugMatch.logic.skipConsumed.toInt & 4) != 0
        if (!checked && dut.sink.logic.uopsOut.valid.toBoolean) {
          assert(!dut.sink.logic.uopsOut.payload(0).debugBreakValid.toBoolean)
          // The breakpoint was SKIPPED, so debugBreakValid is False -- and with it False,
          // debugBreakSlot is a DON'T-CARE. MicroOpAssembler defaults every uop to
          // {debugBreakValid=False, debugBreakSlot=0} (:59, :143), and DecodeStage overrides
          // the slot ONLY on a match (:2666-2668), so a skipped macro legitimately carries 0.
          //
          // Asserting slot==2 here made this test fail ~40% of runs (measured: 2 failures in 5
          // consecutive standalone runs on an unmodified tree). It asserted a value the design
          // never promised. The real property -- that the break was SUPPRESSED -- is the
          // assertion above; the consumption pulse is checked by `consumed` below. The slot IS
          // still asserted earlier in this test, where debugBreakValid is True and it means
          // something.
          checked = true
        }
      }
      assert(checked, "skipped breakpoint macro never reached decode output")
      assert(consumed, "skip-once consumption pulse for slot 2 was not observed")
    }
  }
}
