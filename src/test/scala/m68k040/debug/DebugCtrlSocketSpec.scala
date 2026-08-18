package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The SoC-fabric control group (`cpu_socket.vh` section 6) and the `cfg_wipe` escape
  * hatch. Every POR default and clamp bound is taken from `DebugRegMap`, which is
  * generated from `tools/debug/debug_regmap.def`, which in turn is checked against
  * `macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:1805,1814,2202-2206`. */
class DebugCtrlSocketSpec extends AnyFunSuite {

  private val DRC_CFG_WIPE = 1L << 0

  private def settle(dut: DebugCtrlDut): Unit = {
    dut.clockDomain.forkStimulus(10)
    DbgAxiDriver.idle(dut.axi)
    dut.dbg.logic.initDoneSeen #= false
    dut.clockDomain.waitSampling(20)
  }

  test("power-on defaults match the deployed controller") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_POR,
        s"cpu_ram_window_lg2 POR = ${dut.dbg.logic.ramWindowLg2.toInt}, " +
        s"expected ${DebugRegMap.RAM_WINDOW_LG2_POR}")
      assert(dut.dbg.logic.monSense.toInt == DebugRegMap.MON_SENSE_POR,
        s"cpu_mon_sense POR = ${dut.dbg.logic.monSense.toInt}, " +
        s"expected ${DebugRegMap.MON_SENSE_POR}")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == DebugRegMap.RAM_WINDOW_LG2_POR.toLong)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == DebugRegMap.MON_SENSE_POR.toLong)
    }
  }

  test("the RAM window round-trips and drives cpu_ram_window_lg2") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- DebugRegMap.RAM_WINDOW_LG2_MIN to DebugRegMap.RAM_WINDOW_LG2_MAX) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong) == 0)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == v,
          s"cpu_ram_window_lg2 = ${dut.dbg.logic.ramWindowLg2.toInt}, wrote $v")
        assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == v.toLong)
      }
    }
  }

  test("the RAM window clamps below the minimum and above the maximum") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- 0 until DebugRegMap.RAM_WINDOW_LG2_MIN) {
        DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_MIN,
          s"writing $v must clamp up to ${DebugRegMap.RAM_WINDOW_LG2_MIN}")
      }
      for (v <- (DebugRegMap.RAM_WINDOW_LG2_MAX + 1) until 64) {
        DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_MAX,
          s"writing $v must clamp down to ${DebugRegMap.RAM_WINDOW_LG2_MAX}")
      }
    }
  }

  test("all 128 monitor-sense encodings are accepted unclamped") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- 0 until 128) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_MON_SENSE.toLong, v.toLong) == 0)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.monSense.toInt == v,
          s"cpu_mon_sense = ${dut.dbg.logic.monSense.toInt}, wrote $v")
      }
      // Bits above 6 are ignored, not stored.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0xFFL)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.monSense.toInt == 0x7F, "only bits [6:0] are stored")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == 0x7FL, "the readback must not leak bit 7")
    }
  }

  test("both fields honour byte strobes") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 24L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x2AL)
      dut.clockDomain.waitSampling(2)
      // WSTRB with byte 0 masked off must leave both untouched.
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 0x0000001EL, strb = 0xE)
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong, 0x00000000L, strb = 0xE)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.ramWindowLg2.toInt == 24, "byte 0 unstrobed must not change it")
      assert(dut.dbg.logic.monSense.toInt == 0x2A, "byte 0 unstrobed must not change it")
    }
  }

  test("cfg_wipe restores exactly the deployed wipe list and answers its own write") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      // Move everything away from its POR value, including a bit the deployed wipe
      // deliberately does NOT clear.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 30L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x7FL)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 1L << 4)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean)

      // The wipe's own write MUST receive its B response -- spec 15.3 and
      // debug_ctrl.v:3016-3024: "Deliberately NOT a reset: it leaves the AXI slave FSM
      // alone, so the very write that requested the wipe still receives its B response."
      val resp = DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE)
      assert(resp == 0, s"the cfg-wipe write must answer OKAY, got $resp")
      dut.clockDomain.waitSampling(3)

      assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_POR,
        "cfg_wipe must restore the RAM window to its POR default")
      assert(dut.dbg.logic.monSense.toInt == DebugRegMap.MON_SENSE_POR,
        "cfg_wipe must restore the monitor sense to its POR default")
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "cfg_wipe must NOT clear cold-reset hold -- the deployed wipe list does not, and " +
        "feature bit 2 has to mean the same thing on both cores (spec 15.3)")

      // The slave is still alive afterwards.
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_VERSION.toLong) == DebugRegMap.VERSION_VALUE.toLong)
    }
  }

  test("cfg_wipe is a one-cycle strobe, not a level") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.csr.cfgWipe.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"cfg_wipe was high for $high cycles, expected exactly 1")
      // A wipe request with byte 0 unstrobed must not fire at all.
      var high2 = 0
      val watcher2 = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.csr.cfgWipe.toBoolean) high2 += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE, strb = 0xE)
      dut.clockDomain.waitSampling(20)
      watcher2.terminate()
      assert(high2 == 0, "an unstrobed byte 0 must not raise cfg_wipe")
    }
  }
}
