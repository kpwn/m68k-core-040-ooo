package m68k040.socket

import m68k040.execute.regfile.{IntRegFileService, RegFileWritePort, RegFileReadPort}
import m68k040.execute.regfile.RegFilePluginInt
import m68k040.services.CommittedMapService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin (docs/BUG_calibration_word_misplaced_0d00.md Part 100/101 fix
  * verification, `ResetVectorA7RaceSpec`): wires a REAL `ResetVectorFsm` against a REAL
  * `RegFilePluginInt.initDone`, EXACTLY the way production `ResetVectorPlugin.scala` wires
  * it (`fsm.io.regInitDone := host[RegFilePluginInt].logic.initDone`), and issues its SSP
  * write through a fresh int-PRF write port addressed the same way `FullCoreSynth`'s real
  * `a7Wr` is (`CommittedMapService.intPhys(15)`, i.e. `committedPhysA7`) -- so the fix-
  * verification tests exercise the ACTUAL, real `ResetVectorFsm` class (not a re-
  * implementation of its gate logic), through the ACTUAL production cross-plugin wiring
  * shape, not a stand-in.
  *
  * `dummyWr` is a throwaway FIRST write port (always invalid, its own fresh `sharingKey`)
  * registered ahead of the real one, same reason `A7RaceDriverPlugin.scala` registers one:
  * it pushes `wr` onto `RegFilePlugin`'s physical write index >= 1 (the plain
  * `enable = w.valid && initDone` gate), matching `a7Wr`'s real index-5 slot in the full
  * core, rather than physical index 0 (a different mechanism, address-muxed to the sweep
  * counter). AXI request/response ports are re-exposed for the testbench to play the
  * merge-arbiter role, the same shape `ResetVectorIntegrationSpec.TestResetVectorPlugin`
  * already uses. */
class FsmA7DriverPlugin extends FiberPlugin {
  var dummyWr: RegFileWritePort = null
  var wr: RegFileWritePort = null
  var rd: RegFileReadPort = null

  during setup {
    val rf = host[IntRegFileService]
    dummyWr = rf.newWrite(latency = 1)                            // fresh key -> phys[0]
    wr      = rf.newWrite(latency = 1, sharingKey = "fsmA7race")   // fresh key -> phys[1]
    rd      = rf.newRead(forceNoBypass = true)
  }

  val logic = during build new Area {
    dummyWr.valid := False; dummyWr.address := 0; dummyWr.data := 0

    val rfPlugin = host[RegFilePluginInt]
    val cmap     = host[CommittedMapService]
    val fsm      = new ResetVectorFsm(128)

    // THE fix under test, wired exactly as ResetVectorPlugin.scala wires it in production.
    fsm.io.regInitDone := rfPlugin.logic.initDone

    // AXI request/response: exposed for the testbench to drive, same shape
    // ResetVectorIntegrationSpec.TestResetVectorPlugin already uses.
    val arValid = out Bool(); arValid := fsm.io.arValid
    val arAddr  = out UInt(32 bits); arAddr := fsm.io.arAddr
    val arReady = in  Bool(); fsm.io.arReady := arReady
    val rValid  = in  Bool(); fsm.io.rValid  := rValid
    val rData   = in  Bits(128 bits); fsm.io.rData := rData
    val rResp   = in  Bits(2 bits); fsm.io.rResp := rResp
    val rReady  = out Bool(); rReady := fsm.io.rReady

    // FSM outputs, exposed raw for cycle-accurate ordering assertions.
    val sspWriteValid = out Bool(); sspWriteValid := fsm.io.sspWriteValid
    val sspDataOut     = out UInt(32 bits); sspDataOut := fsm.io.sspData
    val redirectValid = out Bool(); redirectValid := fsm.io.redirectValid
    val haltPulse      = out Bool(); haltPulse := fsm.io.haltPulse

    // Direct observation of the real sweep's own completion flag -- not re-derived here.
    val regInitDone = out Bool(); regInitDone := rfPlugin.logic.initDone

    val committedPhysA7 = cmap.intPhys(15)
    val committedPhysA7Out = out UInt(committedPhysA7.getWidth bits)
    committedPhysA7Out := committedPhysA7

    // Mirrors FullCoreSynth.scala:528-529's real a7Wr.valid/.address wiring for the
    // rvSspValid arm (the exception/sysReg arms don't exist in this minimal DUT).
    wr.valid   := fsm.io.sspWriteValid
    wr.address := committedPhysA7.resize(wr.address.getWidth)
    wr.data    := fsm.io.sspData.asBits

    rd.addr := committedPhysA7.resize(rd.addr.getWidth)
    val rdData = out UInt(32 bits)
    rdData := rd.data.asUInt
  }
}
