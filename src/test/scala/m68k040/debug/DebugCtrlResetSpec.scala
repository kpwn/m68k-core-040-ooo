package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 10.1: "The AXI slave and cold-reset-hold register must never be reset by
  * the CPU reset they request. A synchronized CPU-reset edge increments the surviving
  * reset counter and clears runtime state without disturbing accepted AXI handshakes or
  * host configuration."
  *
  * This is the property the sibling's 2026-07-26 fix exists for, so it is tested directly
  * rather than inferred: configuration written before a CPU reset must still be there
  * afterwards, and a transaction accepted before the reset must still be answered.
  *
  * DEVIATION from the task-11 brief's literal test text: every place the brief held
  * `dut.clockDomain.assertReset()` across a `waitSampling(n)` call is rewritten to
  * `sleep(n * PERIOD)` instead. `ClockDomain.waitSampling`'s edge counter only advances
  * while `isSamplingEnable()` is true, which requires `dut.clockDomain`'s own reset to be
  * deasserted (confirmed by decompiling `SimClockDomainPimper`, see the note next to
  * `sleep(` in `DebugCtrlCsrSpec`'s "a CPU reset wipes..." test) -- holding that same
  * reset across a `waitSampling` call therefore hangs the simulator unconditionally,
  * which is exactly the class of test this file exists to run. */
class DebugCtrlResetSpec extends AnyFunSuite {

  private val DRC_CFG_WIPE    = 1L << 0
  private val DRC_COUNT_CLEAR = 1L << 1
  private val PERIOD          = 10L

  private def settle(dut: DebugCtrlDut): Unit = {
    dut.clockDomain.forkStimulus(10)
    DbgAxiDriver.idle(dut.axi)
    dut.dbg.logic.initDoneSeen #= false
    dut.clockDomain.waitSampling(20)
  }

  /** Assert then release the SOCKET reset -- the one the debug domain observes but must
    * not consume. Uses `sleep(ns)` rather than `waitSampling(cycles)` while reset is held:
    * see the class-level DEVIATION note. */
  private def cpuReset(dut: DebugCtrlDut, cycles: Int = 5): Unit = {
    dut.clockDomain.assertReset()
    sleep(cycles * PERIOD)
    dut.clockDomain.deassertReset()
    dut.clockDomain.waitSampling(5)
  }

  /** Only the HOST-CONFIGURATION half of spec section 15.1's split is tested here. Its
    * mirror image -- that the CPU-COUPLED RUNTIME half (the sticky init-done latch and the
    * CONTROL bit 3 override) is WIPED by the same edge -- is
    * `DebugCtrlCsrSpec`'s "a CPU reset wipes the init-done state but NOT the cold-reset
    * hold", next to the registers it concerns. The two together are the whole property;
    * neither alone would catch a blanket-survive or a blanket-wipe implementation. */
  test("host configuration survives a CPU reset") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 29L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x4BL)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 1L << 4)
      dut.clockDomain.waitSampling(2)

      cpuReset(dut)

      assert(dut.dbg.logic.ramWindowLg2.toInt == 29,
        s"the RAM window was wiped by the CPU reset (${dut.dbg.logic.ramWindowLg2.toInt})")
      assert(dut.dbg.logic.monSense.toInt == 0x4B,
        s"the monitor sense was wiped by the CPU reset (${dut.dbg.logic.monSense.toInt})")
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "cold-reset hold cleared itself across the reset it requested -- the exact " +
        "self-clearing bug debug_reset_ctl.v exists to prevent")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == 29L, "readback after reset")
    }
  }

  test("an accepted transaction is still answered when CPU reset asserts mid-flight") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      settle(dut)
      // Accept AW+W, hold BREADY low, then assert the CPU reset. Spec 3.1: "never clear
      // an accepted request merely because CPU reset asserted".
      b.bready #= false
      b.awaddr #= DebugRegMap.OFF_RAM_WINDOW_LG2.toLong; b.awvalid #= true
      b.wdata  #= 27L; b.wstrb #= 0xF; b.wvalid #= true
      dut.clockDomain.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
      b.awvalid #= false; b.wvalid #= false
      dut.clockDomain.waitSamplingWhere(b.bvalid.toBoolean)

      dut.clockDomain.assertReset()
      sleep(6 * PERIOD)
      assert(b.bvalid.toBoolean, "BVALID was dropped by the CPU reset")
      assert(b.bresp.toInt == 0, "BRESP changed across the CPU reset")
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(3)
      assert(b.bvalid.toBoolean, "BVALID was dropped when the CPU reset released")

      b.bready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.bvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "BVALID never cleared")
      assert(dut.dbg.logic.ramWindowLg2.toInt == 27, "the accepted write did not take effect")
    }
  }

  test("the CPU-reset counter counts edges and survives the resets it counts") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      def count(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      def low16(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) & 0xFFFFL

      assert(count() == 0, "the counter must start at zero")
      assert(low16() == 0, "OFF_DBG_RESET_CTL[15:0] reads zero (deployed layout)")
      for (n <- 1 to 5) {
        cpuReset(dut)
        assert(count() == n.toLong, s"after $n resets the counter reads ${count()}")
      }
    }
  }

  test("OFF_DBG_RESET_CTL bit 1 clears the counter") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      def count(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      cpuReset(dut); cpuReset(dut); cpuReset(dut)
      assert(count() == 3)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_COUNT_CLEAR) == 0)
      dut.clockDomain.waitSampling(2)
      assert(count() == 0, "bit 1 must clear the counter")
      // The clear must not be a level: another reset still counts.
      cpuReset(dut)
      assert(count() == 1)
      // And it must respect the byte strobe.
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_COUNT_CLEAR, strb = 0xE)
      dut.clockDomain.waitSampling(2)
      assert(count() == 1, "an unstrobed byte 0 must not clear the counter")
    }
  }

  test("a reset that is already asserted when the debug domain wakes does not count") {
    M68kSim().compile(new DebugCtrlDut(porCyclesArg = 8)).doSim { dut =>
      // Hold the socket reset asserted across the whole debug POR window. The edge
      // detector's reset value is 1 precisely so this does not manufacture an edge
      // (debug_reset_ctl.v:133-142).
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      sleep(30 * PERIOD)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(10)
      val c = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c == 0, s"a reset already high at POR exit manufactured $c spurious edge(s)")
      cpuReset(dut)
      val c2 = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c2 == 1, s"a genuine later edge must still count (got $c2)")
    }
  }

  test("the counter saturates instead of wrapping back through zero") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      // Force the counter to its top value, then prove one more edge leaves it there.
      // Zero means "no reset observed since clear" and must not be reachable by
      // wraparound (spec 15.4). The poke targets a simPublic register and is stable
      // because the very next edge re-evaluates the saturating guard and holds it; if a
      // future SpinalSim rejects poking an internal Reg, replace this line with 0xFFFF
      // real cpuReset(dut) calls -- slower, same property.
      dut.dbg.logic.csr.cpuResetCount #= 0xFFFF
      dut.clockDomain.waitSampling(2)
      cpuReset(dut)
      val c = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c == 0xFFFFL, f"the counter wrapped to 0x$c%X instead of saturating")
    }
  }

  test("the debug domain is untouched by a CPU reset held for a long time") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x33L)
      dut.clockDomain.waitSampling(2)
      // The Q700 platform holds cpu_rst for the WHOLE boot window (MIG calibration, the
      // SD->DDR ROM copy, a 50 ms settle) -- the window an operator most needs the
      // debugger alive in.
      dut.clockDomain.assertReset()

      // "The slave must still serve reads with the reset asserted" cannot be exercised
      // through `DbgAxiDriver.read` here: it polls with `waitSamplingWhere` on
      // `dut.clockDomain`, and that is the exact ClockDomain instance whose reset this
      // test is holding, so that call would hit the very hang the class-level DEVIATION
      // note describes (confirmed empirically: the brief's original form, calling
      // `DbgAxiDriver.read` while `dut.clockDomain` was still in reset, hung for 300s+ in
      // this session). The underlying RTL simulation itself keeps advancing on every real
      // clock edge regardless -- only the harness's own edge-counting bookkeeping is
      // gated on that reset -- so a raw poke-and-`sleep` transaction (RREADY held low so
      // RVALID latches and cannot be missed by an unaligned poll, mirroring the
      // hold-then-check pattern the "accepted transaction" test above uses for BVALID)
      // exercises the real property without going through that bookkeeping.
      // ARREADY is only high while idle and correctly DROPS the instant the request is
      // captured (single-outstanding FSM, spec 3.1) -- so the property to check is not
      // "ARREADY stays high", it is "the request gets captured and answered". RREADY is
      // held low throughout so RVALID/RDATA latch and stay stable for inspection,
      // mirroring the hold-then-check pattern the "accepted transaction" test above uses
      // for BVALID.
      b.rready #= false
      b.araddr #= DebugRegMap.OFF_VERSION.toLong; b.arvalid #= true
      sleep(4 * PERIOD)
      b.arvalid #= false
      sleep(6 * PERIOD)
      assert(b.rvalid.toBoolean, "no RVALID from the debug slave while the CPU reset was held")
      assert((b.rdata.toLong & 0xFFFFFFFFL) == DebugRegMap.VERSION_VALUE.toLong,
        "the debug slave answered the wrong data while the CPU reset was held")
      b.rready #= true
      sleep(4 * PERIOD)

      sleep(380 * PERIOD)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(5)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == 0x33L, "configuration was lost")
      assert(dut.dbg.logic.monSense.toInt == 0x33)
    }
  }
}
