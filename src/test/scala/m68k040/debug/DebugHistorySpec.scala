package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class DebugHistorySpec extends AnyFunSuite {
  class Dut(depth: Int = 32, retireWidth: Int = 2) extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val history = new DebugHistoryStubPlugin(retireWidth)
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 3, historyDepth = depth)
    db.on { host.asHostOf(Seq[FiberPlugin](history, dbg)) }
    def axi = dbg.logic.dbgAxi
  }

  /** Drive exactly `n` single-cycle exception-entry pulses, one per clock, with a
    * one-cycle idle gap after every third so the count cannot be an artefact of a
    * level rather than an edge. Returns `n` for readability at the call site. */
  private def driveExceptions(dut: Dut, n: Int): Int = {
    val cd = dut.clockDomain
    for (i <- 0 until n) {
      dut.history.logic.exceptionValid #= true
      dut.history.logic.exceptionVector #= (i & 0xff)
      dut.history.logic.exceptionPc #= 0x40000000L + i * 4
      dut.history.logic.faultAddress #= 0x50000000L + i * 8
      dut.history.logic.handlerPc #= 0x60000000L + i * 16
      cd.waitSampling()
      if (i % 3 == 2) { dut.history.logic.exceptionValid #= false; cd.waitSampling() }
    }
    dut.history.logic.exceptionValid #= false
    cd.waitSampling(2)
    n
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

  test("four-wide PC history compacts sparse macro boundaries and wraps every bank") {
    M68kSim().compile(new Dut(retireWidth = 4)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid.foreach(_ #= false)
      dut.history.logic.branchValid #= false; dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)
      val expected = Array.fill[Long](32)(0L)
      var head = 0
      var sequence = 0L
      for (round <- 0 until 4; mask <- 0 until 16) {
        for (lane <- 0 until 4) {
          val valid = (mask & (1 << lane)) != 0
          dut.history.logic.pcValid(lane) #= valid
          dut.history.logic.pc(lane) #= 0x10000000L + sequence * 2
          if (valid) {
            expected(head) = 0x10000000L + sequence * 2
            head = (head + 1) % 32; sequence += 1
          }
        }
        cd.waitSampling()
      }
      dut.history.logic.pcValid.foreach(_ #= false)
      cd.waitSampling(2)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_PC_TRACE_HEAD) == head)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_PERF_INST_LO) == sequence)
      for (index <- 0 until 32)
        assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_PC_TRACE_BODY + index * 4) == expected(index))
    }
  }

  // ── OFF_EXC_COUNT ─────────────────────────────────────────────────────────
  // Before 2026-09-06 this offset had no serving arm at all and the read mux
  // returned 0 for unlisted offsets by design, so `exc_count` read a constant
  // zero on cpu040 and an investigation concluded interrupts were being lost
  // (the real rate was 154/s). The assertions below therefore check the VALUE
  // against a known stimulus count, not merely that the register moves: a
  // register that changes is not a register that counts.
  test("OFF_EXC_COUNT counts every exception entry against a known stimulus") {
    M68kSim().compile(new Dut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid(0) #= false; dut.history.logic.pcValid(1) #= false
      dut.history.logic.branchValid #= false; dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)

      // Reset value, and a control: no stimulus, no count.
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == 0L,
        "OFF_EXC_COUNT must be 0 out of reset")
      cd.waitSampling(50)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == 0L,
        "OFF_EXC_COUNT must not advance without exception entries")

      // First batch: fewer than the 32-entry ring, so head and count agree and a
      // head-as-count reading would still look right. This is the case that made
      // the wrong conclusion plausible.
      val first = driveExceptions(dut, 20)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == first.toLong,
        "OFF_EXC_COUNT must equal the number of entries driven")
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_RING_HEAD) == first.toLong)

      // Second batch takes the total PAST the ring wrap. The head folds to
      // (total % 32); the counter must not. This is the assertion that kills
      // "read OFF_EXC_RING_HEAD as an exception count".
      val second = driveExceptions(dut, 37)
      val total = first + second // 57
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == total.toLong,
        s"OFF_EXC_COUNT must be $total after $total entries")
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_RING_HEAD) == (total % 32).toLong,
        "ring head must have wrapped -- otherwise this test is not testing wrap")
      assert(total % 32 != total, "stimulus must cross the ring wrap to be meaningful")

      // Quiescent again: the counter holds, it does not free-run on the clock.
      cd.waitSampling(200)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == total.toLong,
        "OFF_EXC_COUNT must hold while no exception entries occur")
    }
  }

  test("OFF_EXC_COUNT is served even when the forensic ring is not built") {
    // historyDepth = 0: no PC trace, no branch ring, no exception ring, and
    // therefore no ring head to misread. The counter must still answer, because
    // "how many exceptions has this core taken" is a question about the core,
    // not about the debug ring.
    M68kSim().compile(new Dut(depth = 0)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid(0) #= false; dut.history.logic.pcValid(1) #= false
      dut.history.logic.branchValid #= false; dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)

      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_RING_HEAD) == 0L)
      val n = driveExceptions(dut, 41)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_COUNT) == n.toLong,
        s"OFF_EXC_COUNT must be $n with historyDepth = 0")
      // The ring genuinely is absent -- otherwise the previous assertion would
      // not be evidence for independence from it.
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_EXC_RING_HEAD) == 0L,
        "historyDepth = 0 must leave the ring head tied to zero")
    }
  }
}
