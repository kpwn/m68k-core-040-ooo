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
}
