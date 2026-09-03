package m68k040.socket

import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Hosts `ResetVectorFsm` and connects it to the merge arbiter's fourth read owner and to
  * `FetchAlignPlugin`'s new internal redirect source.
  *
  * ==D16: `enable` defaults FALSE and that is load-bearing==
  * With it false this plugin elaborates to NOTHING, so the external `redirect` port keeps
  * its current meaning and every existing lock-step spec, directed test and the
  * `M68kFullCoreSynth` OOC/FMax target are untouched. `M68kSocketTop` sets it true. This
  * mirrors v1's `FETCH_RESET_VECTORS` parameter and the reason v1 has one
  * (`if_stage.v:20-23`: "the directed-asm test harness ... pre-arranges memory for a
  * specific PC and doesn't care about SSP").
  *
  * ==Plugin ordering==
  * AFTER `AxiDMergePlugin` (whose `rv*` wires it drives), AFTER `FetchAlignPlugin` (whose
  * `resetRedirect` it drives), AFTER `RegFilePluginInt` (whose `initDone` gates the SSP
  * write -- docs/BUG_calibration_word_misplaced_0d00.md Part 100/101; read via a direct
  * `host[RegFilePluginInt].logic.initDone` reach-through, the same established pattern
  * `FullCoreSynth.scala` itself uses for cross-plugin signal wiring, e.g.
  * `host[IcachePlugin].logic.invalidateAll`), BEFORE `BackendWiringPlugin` (which reads
  * `sspWriteValid` and `haltPulse`). SpinalHDL's Fiber/Handle machinery resolves
  * `host[RegFilePluginInt].logic` lazily regardless of literal instantiation order, but
  * `SocketTop.scala`/`FullCoreSynth.scala` both already list `RegFilePluginInt` earlier in
  * their plugin sequences, so this stays a documented-and-true ordering claim too. */
class ResetVectorPlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    // Declared unconditionally so BackendWiringPlugin can read them either way; when
    // disabled they are constants and every consumer folds away.
    val sspWriteValid = Bool()
    val sspData       = UInt(32 bits)
    val haltPulse     = Bool()

    if (!enable) {
      sspWriteValid := False
      sspData       := U(0, 32 bits)
      haltPulse     := False
    } else {
      val arb = host[AxiDMergePlugin]
      val fa  = host[FetchAlignPlugin]
      val rf  = host[m68k040.execute.regfile.RegFilePluginInt]
      val fsm = new ResetVectorFsm(arb.logic.merge.io.rvRData.getWidth)

      arb.logic.rvArValid := fsm.io.arValid
      arb.logic.rvArAddr  := fsm.io.arAddr
      fsm.io.arReady      := arb.logic.rvArReady
      fsm.io.rValid       := arb.logic.rvRValid
      fsm.io.rData        := arb.logic.rvRData
      fsm.io.rResp        := arb.logic.rvRResp
      arb.logic.rvRReady  := fsm.io.rReady

      // Part 100/101 fix: hold the SSP write off until the int PRF's post-reset zero-sweep
      // is done, so it can never land inside the sweep and be silently dropped.
      fsm.io.regInitDone  := rf.logic.initDone

      fa.logic.resetRedirect.valid   := fsm.io.redirectValid
      fa.logic.resetRedirect.payload := fsm.io.redirectPc

      sspWriteValid := fsm.io.sspWriteValid
      sspData       := fsm.io.sspData
      haltPulse     := fsm.io.haltPulse
      sspWriteValid.simPublic(); sspData.simPublic(); haltPulse.simPublic()
    }
  }
}
