package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class DebugHistorySpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val history = new DebugHistoryStubPlugin
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 3, historyDepth = 32)
    db.on { host.asHostOf(Seq[FiberPlugin](history, dbg)) }
    def axi = dbg.logic.dbgAxi
  }

  test("32-entry committed histories preserve dual-retire order and wrap") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid(0) #= false; dut.history.logic.pcValid(1) #= false
      dut.history.logic.branchValid #= false; dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)

      // 34 macro completions, two per cycle. Slot 0 must precede slot 1 in the ring.
      for (pair <- 0 until 17) {
        dut.history.logic.pcValid(0) #= true; dut.history.logic.pcValid(1) #= true
        dut.history.logic.pc(0) #= 0x10000000L + pair * 8
        dut.history.logic.pc(1) #= 0x10000004L + pair * 8
        cd.waitSampling()
      }
      dut.history.logic.pcValid(0) #= false; dut.history.logic.pcValid(1) #= false

      // Branch and exception streams are independently single-event-per-cycle.
      for (i <- 0 until 34) {
        dut.history.logic.branchValid #= true
        dut.history.logic.branchPc #= 0x20000000L + i * 4
        dut.history.logic.branchNextPc #= 0x30000000L + i * 8
        dut.history.logic.branchTaken #= ((i & 1) != 0)
        dut.history.logic.branchMispredicted #= ((i % 3) == 0)
        dut.history.logic.branchType #= (i & 3)
        dut.history.logic.exceptionValid #= true
        dut.history.logic.exceptionVector #= (i & 0xff)
        dut.history.logic.exceptionPc #= 0x40000000L + i * 4
        dut.history.logic.faultAddress #= 0x50000000L + i * 8
        dut.history.logic.handlerPc #= 0x60000000L + i * 16
        cd.waitSampling()
      }
      dut.history.logic.branchValid #= false; dut.history.logic.exceptionValid #= false
      cd.waitSampling(2)

      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_CAP_TRACE) == 0x00200020L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_CAP_TRACE2) == 0x20L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_PC_TRACE_HEAD) == 2L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_BRANCH_RING_HEAD) == 2L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_RING_HEAD) == 2L)

      for (index <- 0 until 32) {
        val event = if (index < 2) index + 32 else index
        val pc = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_PC_TRACE_BODY + index * 4)
        assert(pc == 0x10000000L + event * 4,
          f"PC ring[$index] = 0x$pc%08x, event=$event")

        val brBase = DebugRegMap.OFF_BRANCH_RING_BODY + index * 16
        assert(DbgAxiDriver.read(dut.axi, cd, brBase) == 0x20000000L + event * 4)
        assert(DbgAxiDriver.read(dut.axi, cd, brBase + 4) == 0x30000000L + event * 8)
        val brMeta = (((event & 3) << 2) | (if (event % 3 == 0) 2 else 0) |
          (if ((event & 1) != 0) 1 else 0)).toLong
        assert(DbgAxiDriver.read(dut.axi, cd, brBase + 8) == brMeta)
        assert(DbgAxiDriver.read(dut.axi, cd, brBase + 12) == 0L)

        val exBase = DebugRegMap.OFF_EXC_RING_BODY + index * 16
        assert(DbgAxiDriver.read(dut.axi, cd, exBase) == (event & 0xff))
        assert(DbgAxiDriver.read(dut.axi, cd, exBase + 4) == 0x40000000L + event * 4)
        assert(DbgAxiDriver.read(dut.axi, cd, exBase + 8) == 0x50000000L + event * 8)
        assert(DbgAxiDriver.read(dut.axi, cd, exBase + 12) == 0x60000000L + event * 16)
      }
    }
  }
}
