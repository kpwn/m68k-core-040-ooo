package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Non-vacuous gate for the registered complex-resume FMax boundary.
  *
  * The real frontend presents a brief PC-indexed MOVEM, the exact supported detector arm
  * whose old combinational Flow formed the fresh route's ten worst setup paths. The test
  * observes the local detector and the public action independently: an action in the same
  * cycle, a missing C+1 action, a changed target, or a duplicate pulse all fail.
  */
class UcComplexResumePipelineSpec extends AnyFunSuite {
  /** Test-only owner for DecodeStage's intentionally overrideable flush wire. */
  class FlushDriverPlugin(dec: DecodeStage) extends FiberPlugin {
    val logic = during build new Area {
      val flush = in(Bool())
      dec.logic.pipeFlush := flush
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ic   = new IcachePlugin
    val fa   = new FetchAlignPlugin
    val dec  = new DecodeStage
    val sink = new UopSinkPlugin
    val flushDrv = new FlushDriverPlugin(dec)
    db.on {
      host.asHostOf(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin,
        ic, fa, dec, sink, flushDrv))
    }
  }

  test("complex MOVEM resume is one registered pulse with the exact fall-through", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L
      val detectorFlushPc = base + 0x100
      val pendingFlushPc = base + 0x200

      // MOVEM.L (d8,PC,D6.L*2),D2/D3/D7/A0:
      //   opword, register mask, brief index extension. Decode's supported resume contract
      //   is instruction fall-through = PC + 6, independent of the data-side EA.
      val words = Array.fill(384)(0x4e71)
      for (wordOff <- Seq(0, 0x80, 0x100)) {
        words(wordOff + 0) = 0x4cfb
        words(wordOff + 1) = 0x018c
        words(wordOff + 2) = 0x6a00
      }
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words.toSeq)
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= false
      dut.flushDrv.logic.flush #= false
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

      // Normal association: detector at C, exactly one target-preserving action at C+1.
      redirect(base)
      val target = waitForDetector(base)
      cd.waitSampling()
      assert(dut.dec.logic.ucComplexResume.valid.toBoolean,
        "accepted detector did not produce its C+1 action")
      assert((dut.dec.logic.ucComplexResume.payload.toLong & 0xffffffffL) == target,
        "registered complex-resume target changed")
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "complex-resume action was wider than one cycle")

      // Flush coincident with the detector must prevent capture and any later action.
      redirect(detectorFlushPc)
      waitForDetector(detectorFlushPc)
      dut.flushDrv.logic.flush #= true
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "detector-cycle flush did not suppress complex-resume capture")
      dut.flushDrv.logic.flush #= false
      for (_ <- 0 until 4) {
        cd.waitSampling()
        assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
          "flushed detector produced a delayed stale resume")
      }

      // Once the target register is pending, a flush arriving before the consumer edge
      // must combinationally hide the Flow and clear it on the next edge.
      redirect(pendingFlushPc)
      waitForDetector(pendingFlushPc)
      cd.waitSampling()
      assert(dut.dec.logic.ucComplexResumeValidReg.toBoolean,
        "pending-action phase never armed the resume register")
      dut.flushDrv.logic.flush #= true
      sleep(1)
      assert(!dut.dec.logic.ucComplexResume.valid.toBoolean,
        "pending-action flush did not suppress the live resume Flow")
      cd.waitSampling()
      assert(!dut.dec.logic.ucComplexResumeValidReg.toBoolean,
        "pending-action flush did not clear the resume register")
    }
  }
}
