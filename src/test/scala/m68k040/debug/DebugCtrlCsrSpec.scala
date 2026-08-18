package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Stage 1 CSR values: identity, capability, CONTROL/STATUS and the SoC-fabric
  * configuration registers. Every expectation is derived from `DebugRegMap` (which is
  * generated from `tools/debug/debug_regmap.def`) rather than restated as a literal, so a
  * contract change cannot pass by being copied into the test too. */
class DebugCtrlCsrSpec extends AnyFunSuite {

  private val BUILD = 0xC0FFEE01L

  test("OFF_VERSION reads the version epoch") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_VERSION.toLong)
      assert(got == DebugRegMap.VERSION_VALUE.toLong,
        f"OFF_VERSION = 0x$got%08X, expected 0x${DebugRegMap.VERSION_VALUE}%08X")
      assert(got == 0xDEB60100L, "spec section 3.1 fixes the epoch at 0xDEB6_0100")
    }
  }

  test("OFF_BUILD_ID reads back the SoC-supplied constructor parameter") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_BUILD_ID.toLong)
      assert(got == BUILD, f"OFF_BUILD_ID = 0x$got%08X, expected 0x$BUILD%08X")
    }
  }

  test("OFF_BUILD_ID is read-only") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_BUILD_ID.toLong, 0xFFFFFFFFL) == 0, "write must answer OKAY")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_BUILD_ID.toLong) == BUILD, "OFF_BUILD_ID must not be writable")
    }
  }

  test("OFF_FEATURES advertises exactly what this stage implements") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_FEATURES.toLong)
      val want = DebugRegMap.featuresForStage(1).toLong
      assert(got == want, f"OFF_FEATURES = 0x$got%08X, expected 0x$want%08X")
      assert(got == 0x0004000FL, "spec section 15.5 fixes the Stage-1 value")
      // Every bit that is set must belong to a feature this stage really built.
      for ((name, bit, featStage) <- DebugRegMap.features if ((got >> bit) & 1L) == 1L)
        assert(featStage <= 1,
          s"OFF_FEATURES advertises '$name' (bit $bit, stage $featStage) from a stage-1 build")
      // And every optional bit must be clear (spec Stage 1: "Keep every optional feature
      // bit zero").
      for (optional <- Seq("pc_trace", "exc_ring", "break_pc_multi", "halt_exc_mask",
                           "live_arch", "dcache_probe", "perf_counters", "watchpoints",
                           "atrap_bp", "atrap_regcap", "atrap_d0qual",
                           "arch_apply_stays_halted", "arch_dirty_apply",
                           "cache_maint_only", "macro_retire_count", "stop_status_v2")) {
        val bit = DebugRegMap.features.find(_._1 == optional)
          .getOrElse(fail(s"feature '$optional' is missing from DebugRegMap"))._2
        assert(((got >> bit) & 1L) == 0L, s"optional feature '$optional' (bit $bit) must read 0")
      }
    }
  }

  test("OFF_FEATURES is read-only") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_FEATURES.toLong, 0xFFFFFFFFL) == 0)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_FEATURES.toLong) == DebugRegMap.featuresForStage(1).toLong)
    }
  }

  test("OFF_CAP_TRACE reads zero because Stage 1 has no trace memories") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CAP_TRACE.toLong) == 0L,
        "trace depths must be 0 while feature bits 4/5 are clear")
    }
  }

  test("a higher stage parameter advertises strictly more, never less") {
    // The plugin is stage-parameterised so a later tranche cannot forget to widen
    // OFF_FEATURES, and cannot widen it by hand-editing a literal.
    M68kSim().compile(new DebugCtrlDut(stageArg = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_FEATURES.toLong)
      val stage1 = DebugRegMap.featuresForStage(1).toLong
      assert(got == DebugRegMap.featuresForStage(2).toLong)
      assert((got & stage1) == stage1, "stage 2 must not retract a stage-1 bit")
    }
  }
}
