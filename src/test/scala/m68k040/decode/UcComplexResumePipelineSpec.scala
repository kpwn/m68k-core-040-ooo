package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.frontend.{FetchAlignPlugin, ComplexResumeActionPipe}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.DecodeUopService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Non-vacuous gate for the registered complex-resume FMax boundary.
  *
  * The real frontend presents a brief PC-indexed MOVEM, the exact supported detector arm
  * whose old combinational Flow formed the fresh route's ten worst setup paths. The test
  * observes the local detector, Decode service, and frontend action independently: an
  * early action, a missing C+1/C+2 boundary, a changed target, or a duplicate pulse fails.
  */
class UcComplexResumePipelineSpec extends AnyFunSuite {
  class ResumeWiringPlugin extends FiberPlugin {
    val logic = during build new Area {
      val flush = in(Bool())
      val decodeUop = host[DecodeUopService]
      decodeUop.pipeFlush := flush
      val frontendResume = ComplexResumeActionPipe(decodeUop.complexResume, flush)
      val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
      faRedir.valid   := frontendResume.valid
      faRedir.payload := frontendResume.payload
      spinal.core.sim.SimPublic(frontendResume.valid, frontendResume.payload,
        faRedir.valid, faRedir.payload)
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ic   = new IcachePlugin
    val fa   = new FetchAlignPlugin
    val dec  = new DecodeStage
    val sink = new UopSinkPlugin
    val resumeWiring = new ResumeWiringPlugin
    db.on {
      host.asHostOf(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin,
        ic, fa, dec, sink, resumeWiring))
    }
  }

  test("complex MOVEM resume is one registered pulse with the exact fall-through", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L
      val detectorFlushPc = base + 0x100
      val pendingFlushPc = base + 0x200
      val frontendFlushPc = base + 0x300

      // MOVEM.L (d8,PC,D6.L*2),D2/D3/D7/A0:
      //   opword, register mask, brief index extension. Decode's supported resume contract
      //   is instruction fall-through = PC + 6, independent of the data-side EA.
      val words = Array.fill(512)(0x4e71)
      for (wordOff <- Seq(0, 0x80, 0x100, 0x180)) {
        words(wordOff + 0) = 0x4cfb
        words(wordOff + 1) = 0x018c
        words(wordOff + 2) = 0x6a00
      }
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words.toSeq)
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= false
      dut.resumeWiring.logic.flush #= false
      cd.waitSampling(5)

      def redirect(pc: Long): Unit = {
        dut.fa.logic.redirect.valid #= true
        dut.fa.logic.redirect.payload #= pc
        cd.waitSampling()
        dut.fa.logic.redirect.valid #= false
      }

      def waitForDetector(expectedPc: Long): Long = {
        var target = 0L
        var seen = false
        var guard = 0
        while (!seen && guard < 160) {
          cd.waitSampling()
          seen = dut.dec.logic.ucComplexResumeDetect.toBoolean
          if (seen) {
            target = dut.dec.logic.ucComplexResumeTarget.toLong & 0xffffffffL
            assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
              "complex-resume detector acted combinationally")
            assert(target == expectedPc + 6,
              f"MOVEM detector target wrong: got 0x$target%08x expected 0x${expectedPc + 6}%08x")
          }
          guard += 1
        }
        assert(seen, f"never observed the real MOVEM detector at 0x$expectedPc%08x")
        target
      }

      // Normal association: detector C, Decode service C+1, frontend-local action C+2.
      redirect(base)
      val target = waitForDetector(base)
      cd.waitSampling()
      assert(dut.dec.logic.ucComplexResume.valid.toBoolean,
        "accepted detector did not produce its C+1 action")
      assert((dut.dec.logic.ucComplexResume.payload.toLong & 0xffffffffL) == target,
        "registered complex-resume target changed")
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "frontend complex resume acted before C+2")
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "complex-resume action was wider than one cycle")
      assert(dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "Decode service action did not produce its C+2 frontend action")
      assert((dut.resumeWiring.logic.frontendResume.payload.toLong & 0xffffffffL) == target,
        "frontend-local complex-resume target changed")
      assert(dut.resumeWiring.logic.faRedir.valid.toBoolean,
        "C+2 action did not reach the real FetchAlign redirect input")
      assert((dut.resumeWiring.logic.faRedir.payload.toLong & 0xffffffffL) == target,
        "FetchAlign redirect observed the wrong C+2 target")
      cd.waitSampling()
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "frontend complex-resume action was wider than one cycle")

      // Flush coincident with the detector must prevent capture and any later action.
      redirect(detectorFlushPc)
      waitForDetector(detectorFlushPc)
      dut.resumeWiring.logic.flush #= true
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "detector-cycle flush did not suppress complex-resume capture")
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "detector-cycle flush leaked into the frontend action stage")
      dut.resumeWiring.logic.flush #= false
      for (_ <- 0 until 4) {
        cd.waitSampling()
        assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
          "flushed detector produced a delayed stale resume")
      }

      // Once Decode's target register is pending, a flush must hide the service action,
      // prevent capture into the frontend stage, and clear both boundaries.
      redirect(pendingFlushPc)
      waitForDetector(pendingFlushPc)
      cd.waitSampling()
      assert(dut.dec.logic.ucComplexResumeValidReg.toBoolean,
        "pending-action phase never armed the resume register")
      dut.resumeWiring.logic.flush #= true
      sleep(1)
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "pending-action flush did not suppress the live resume Flow")
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "pending Decode action leaked through the frontend stage")
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResumeValidReg.toBoolean,
        "pending-action flush did not clear the resume register")
      dut.resumeWiring.logic.flush #= false

      // Arm the frontend-local register, then cancel it before FetchAlign's consuming
      // edge. The output is combinationally suppressed and the register clears at once.
      redirect(frontendFlushPc)
      waitForDetector(frontendFlushPc)
      cd.waitSampling() // C+1 Decode service pulse
      cd.waitSampling() // C+2 frontend action becomes live
      assert(dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "frontend-action phase never armed the local register")
      dut.resumeWiring.logic.flush #= true
      sleep(1)
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "frontend-pending flush did not suppress the live action")
      assert(!dut.resumeWiring.logic.faRedir.valid.toBoolean,
        "cancelled frontend action still reached FetchAlign")
      cd.waitSampling()
      dut.resumeWiring.logic.flush #= false
      assert(!dut.resumeWiring.logic.frontendResume.valid.toBoolean,
        "frontend-pending flush did not clear the local register")
    }
  }
}
