package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** End-to-end service-boundary check: real dbg_axi CSR bank driving the real ROB halt
  * owner. The ROB's detailed macro-boundary behavior remains covered by RobPluginSpec. */
class DebugCtrlRobIntegrationSpec extends AnyFunSuite {

  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin
    val commit = new RenameCommitSinkPlugin
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 2)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, alloc, rob, commit, dbg)) }

    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  test("dbg_axi manual halt and resume drive the real ROB owner") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 1)
      var status = 0L
      var waited = 0
      while ((status & 1L) == 0 && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      }
      assert((status & 1L) != 0, f"ROB did not reach effective halt; STATUS=0x$status%08X")
      assert((status & (1L << 3)) == 0, f"halted STATUS still reports running: 0x$status%08X")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      do {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      } while ((status & (1L << 3)) == 0 && waited < 100)
      assert((status & 1L) == 0 && (status & (1L << 3)) != 0,
        f"ROB did not resume; STATUS=0x$status%08X")
    }
  }

  test("dbg_axi halt-after is automatic, one-shot, and resumes without retrigger") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)
      assert(dut.rob.logic.debugHaltState.toEnum == m68k040.rob.DebugHaltState.RUNNING,
        "integration DUT did not leave reset in RUNNING")

      // Absolute target zero is already satisfied at reset. Programming both halves
      // followed by HALT_CTL.arm exercises the full CSR -> service -> ROB path.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)

      var status = 0L
      var waited = 0
      while ((status & ((1L << 4) | 1L)) != ((1L << 4) | 1L) && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      }
      assert(dut.rob.logic.debugAutoHaltLatchedReg.toBoolean,
        f"halt-after failed: STATUS=0x$status%08X state=${dut.rob.logic.debugHaltState.toEnum} " +
        s"armed=${dut.rob.logic.haltAfterArmedIn.toBoolean} " +
        s"target=${dut.rob.logic.haltAfterTargetIn.toBigInt} " +
        s"epoch=${dut.rob.logic.haltAfterEpochIn.toInt} " +
        s"cmpArmed=${dut.rob.logic.haltAfterCmpArmedReg.toBoolean} " +
        s"cmpHit=${dut.rob.logic.haltAfterCmpHitReg.toBoolean} " +
        s"pending=${dut.rob.logic.haltAfterComparePending.toBoolean} " +
        s"due=${dut.rob.logic.haltAfterDue.toBoolean}")
      assert((status & 1L) != 0 && (status & (1L << 4)) != 0,
        f"halt-after did not produce an effective automatic halt: STATUS=0x$status%08X")
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & 1L) == 0,
        "the automatic hit did not consume the one-shot arm")

      // A halted debugger normally programs the next run before continue. Sticky
      // auto-halt status must not continuously clear this new arm.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong, 10)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & 1L) != 0,
        "a new halt-after target could not be armed while effectively halted")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      do {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      } while ((status & (1L << 3)) == 0 && waited < 100)
      assert((status & 1L) == 0 && (status & (1L << 3)) != 0)
      cd.waitSampling(20)
      status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
      assert((status & 1L) == 0, "consumed halt-after target retriggered after resume")
    }
  }

  test("deployed HALT, HALT|STEP, zero sequence accepts exactly one step") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 1)
      var waited = 0
      while (!dut.rob.logic.debugHalted.toBoolean && waited < 100) {
        cd.waitSampling(); waited += 1
      }
      assert(dut.rob.logic.debugHalted.toBoolean)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 3)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      while (dut.rob.logic.debugHaltState.toEnum != m68k040.rob.DebugHaltState.STEP_RUNNING && waited < 100) {
        cd.waitSampling(); waited += 1
      }
      assert(dut.rob.logic.debugHaltState.toEnum == m68k040.rob.DebugHaltState.STEP_RUNNING,
        "the trailing zero write must not cancel an accepted step")
      assert(!dut.rob.logic.debugStepRejected.toBoolean)
    }
  }
}
