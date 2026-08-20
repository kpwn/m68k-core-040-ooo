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
                           "cache_maint_only", "macro_retire_count", "stop_status_v2",
                           "branch_ring")) {
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

  test("Stage 3 architectural shadows are byte-strobed, independently dirty, and cfg-wipeable") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 3, withCommitStubArg = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi); cd.waitSampling(20)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_D0, 0x11223344L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_D0, 0xAABBCCDDL, strb = 0x5)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_SSP, 0x55667788L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_DFC, 0x00000006L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_D0) == 0x11BB33DDL)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_SSP) == 0x55667788L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_DFC) == 6L)
      val dirty = dut.dbg.logic.csr.archDirty.toBigInt
      assert(dirty.testBit(0) && dirty.testBit(17) && dirty.testBit(31))
      assert(dirty.bitCount == 3, f"unexpected dirty mask 0x$dirty%08x")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_DBG_RESET_CTL, 1L)
      cd.waitSampling(2)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_D0) == 0L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_SSP) == 0L)
      assert(dut.dbg.logic.csr.archDirty.toBigInt == 0)
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

  // ── CONTROL / STATUS (spec sections 3.3 and 15.2) ───────────────────────────────

  private val CTRL_HALT           = 1L << 0
  private val CTRL_STEP           = 1L << 1
  private val CTRL_SOFT_RST       = 1L << 2
  private val CTRL_INIT_DONE_OVR  = 1L << 3
  private val CTRL_COLD_HOLD      = 1L << 4
  private val CTRL_COLD_PULSE     = 1L << 5
  private val CTRL_STEP_ARM       = 1L << 7

  private val STAT_HALTED       = 1L << 0
  private val STAT_EXC_PENDING  = 1L << 1
  private val STAT_INIT_DONE    = 1L << 2
  private val STAT_RUNNING      = 1L << 3
  private val STAT_AUTO_HALT    = 1L << 4

  test("CONTROL bit 4 (cold-reset hold) is a level that drives cpu_cold_reset_hold") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      assert(!dut.dbg.logic.coldResetHold.toBoolean, "cold-reset hold must be clear at POR")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_HOLD)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean, "cpu_cold_reset_hold must follow bit 4")
      val rb = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((rb & CTRL_COLD_HOLD) != 0, f"CONTROL readback 0x$rb%08X lost bit 4")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      dut.clockDomain.waitSampling(2)
      assert(!dut.dbg.logic.coldResetHold.toBoolean, "writing 0 must release the hold")
    }
  }

  test("CONTROL bit 5 emits a ONE-cycle cpu_cold_reset_pulse") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.coldResetPulse.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_PULSE)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"cpu_cold_reset_pulse was high for $high cycles, expected exactly 1")
    }
  }

  test("CONTROL bit 2 is the deprecated alias for the cold-reset pulse") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.coldResetPulse.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_SOFT_RST)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"the bit-2 alias produced $high pulse cycles, expected exactly 1")
    }
  }

  test("CONTROL halt/step bits are RAZ/WI in Stage 1 -- no pretending") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_HALT | CTRL_STEP | CTRL_STEP_ARM)
      val rb = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((rb & (CTRL_HALT | CTRL_STEP | CTRL_STEP_ARM)) == 0,
        f"CONTROL 0x$rb%08X must read 0 for halt/step in a Stage-1 build (spec 15.2)")
      assert((rb & 0x40L) == 0, "CONTROL bit 6 is reserved and reads 0")
      val st = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert((st & STAT_HALTED) == 0, "STATUS.halted must stay 0 -- there is no halt yet")
      assert((st & STAT_RUNNING) != 0, "STATUS.running must be 1 while not halted")
    }
  }

  test("Stage 2 CONTROL bit 0 sends registered stop/resume commands through DebugCommitService") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)

      var stopPulses = 0
      var resumePulses = 0
      var stepPulses = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.commitStub.logic.stopRequest.toBoolean) stopPulses += 1
          if (dut.commitStub.logic.resumeRequest.toBoolean) resumePulses += 1
          if (dut.commitStub.logic.stepRequest.toBoolean) stepPulses += 1
        }
      }

      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_HALT)
      dut.clockDomain.waitSampling(3)
      val haltedControl = DbgAxiDriver.read(
        dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((haltedControl & CTRL_HALT) != 0,
        f"Stage-2 CONTROL readback 0x$haltedControl%08X lost the manual halt level")
      assert(stopPulses == 1 && resumePulses == 0,
        s"halt write produced stop=$stopPulses resume=$resumePulses pulses")

      // A clear write is a command, not merely a falling-edge detector. Repeating it
      // must still request resume so an automatic halt can be released while the stored
      // manual level was already zero (spec section 6.4).
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      dut.clockDomain.waitSampling(3)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      dut.clockDomain.waitSampling(3)
      assert(stopPulses == 1 && resumePulses == 2,
        s"two explicit clear writes produced stop=$stopPulses resume=$resumePulses pulses")

      // The deployed step write keeps HALT set and adds the self-clearing bit-1
      // command. Bit 7 remains the legacy observation and is deliberately RAZ/WI.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_HALT | CTRL_STEP | CTRL_STEP_ARM)
      dut.clockDomain.waitSampling(3)
      val steppedControl = DbgAxiDriver.read(
        dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert(stepPulses == 1, s"step write produced $stepPulses command pulses")
      assert((steppedControl & CTRL_STEP) == 0, "CONTROL.step must self-clear")
      assert((steppedControl & CTRL_STEP_ARM) == 0, "legacy CONTROL bit 7 remains RAZ/WI")

      // Byte 0 carries CONTROL bit 0. A write that does not strobe it changes neither
      // the readback level nor the command stream.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_HALT, strb = 0xE)
      dut.clockDomain.waitSampling(3)
      assert(stopPulses == 2 && resumePulses == 2 && stepPulses == 1,
        "an unstrobed CONTROL byte 0 emitted a halt/resume command")
      watcher.terminate()
    }
  }

  test("Stage 2 STATUS and PC readback are owned by DebugCommitService") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)

      dut.commitStub.logic.livePcDrive #= 0x40801234L
      dut.commitStub.logic.lastPcDrive #= 0x4080122CL
      dut.commitStub.logic.effectiveHaltDrive #= true
      dut.commitStub.logic.autoHaltDrive #= true
      dut.commitStub.logic.haltReasonDrive #= 4
      dut.clockDomain.waitSampling(2)

      val status = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert((status & STAT_HALTED) != 0 && (status & STAT_RUNNING) == 0,
        f"STATUS 0x$status%08X does not report the service's effective halt")
      assert((status & STAT_AUTO_HALT) != 0,
        f"STATUS 0x$status%08X lost the service's automatic-halt latch")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_PC.toLong) == 0x40801234L)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_LAST_PC.toLong) == 0x4080122CL)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_HALT_REASON.toLong) == 4)
    }
  }

  test("Stage 2 HALT_CTL bit 2 emits one clear-sticky command and is not state") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)
      var clears = 0
      val watcher = fork {
        while (true) {
          cd.waitSampling()
          if (dut.commitStub.logic.clearStickyRequest.toBoolean) clears += 1
        }
      }
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1L << 2)
      cd.waitSampling(4)
      watcher.terminate()
      assert(clears == 1, s"clear-sticky produced $clears command pulses")
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & (1L << 2)) == 0,
        "clear-sticky command must not read back as state")
    }
  }

  test("STATUS halted and running are mutually exclusive") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      val st = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert(((st & STAT_HALTED) != 0) != ((st & STAT_RUNNING) != 0),
        f"STATUS 0x$st%08X violates spec 3.3 mutual exclusion")
      assert((st & STAT_EXC_PENDING) == 0)
      assert((st & STAT_AUTO_HALT) == 0)
      assert((st & ~0x1FL) == 0, f"STATUS 0x$st%08X sets a bit above 4")
    }
  }

  test("init_done_seen latches sticky and CONTROL bit 3 overrides it") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      def status(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert((status() & STAT_INIT_DONE) == 0, "init-done must start clear")

      // The override alone sets the status bit.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_INIT_DONE_OVR)
      assert((status() & STAT_INIT_DONE) != 0, "CONTROL bit 3 must force init-done")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      assert((status() & STAT_INIT_DONE) == 0, "clearing the override must clear it again")

      // A real pulse on the socket input latches permanently.
      dut.dbg.logic.initDoneSeen #= true
      dut.clockDomain.waitSampling(3)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(3)
      assert((status() & STAT_INIT_DONE) != 0,
        "init_done_seen must latch sticky so a late-attaching debugger still sees it")
    }
  }

  test("a CPU reset wipes the init-done state but NOT the cold-reset hold") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      def status(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)

      // Arm all three bits: the override and the sticky latch (both CPU-COUPLED RUNTIME
      // state, spec 15.1) plus the cold-reset hold (host configuration) as the control
      // that proves the wipe is SELECTIVE and not a blanket reset of the debug domain.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_INIT_DONE_OVR | CTRL_COLD_HOLD)
      dut.clockDomain.waitSampling(2)
      dut.dbg.logic.initDoneSeen #= true
      dut.clockDomain.waitSampling(3)
      // Deassert the LEVEL before the reset: it is live truth from the SoC, so leaving it
      // high would legitimately re-latch the sticky bit the cycle after the wipe and the
      // test would be measuring the level, not the wipe.
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(3)
      assert(dut.dbg.logic.csr.initDoneSticky.toBoolean, "the sticky latch did not set")
      assert(dut.dbg.logic.csr.ctrlInitDoneOvr.toBoolean, "CONTROL bit 3 did not set")
      assert((status() & STAT_INIT_DONE) != 0, "STATUS.init_done must be set before the reset")
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "the cold-reset hold (the survives-side control) did not set")

      // Assert and release the SOCKET reset. Task 11 later factors this into a `cpuReset`
      // helper in DebugCtrlResetSpec; Task 9 lands first, so it is written out here.
      //
      // NOTE (Task 9 fix, found via a real simulation hang): `ClockDomain.waitSampling`'s
      // edge counter only advances when `isSamplingEnable` is true, and
      // `isSamplingEnable = isResetDeasserted && isClockEnableAsserted` (confirmed by
      // decompiling SimClockDomainPimper -- isSamplingEnable/waitSampling$1 bytecode).
      // Calling `waitSampling(n)` *while reset is held asserted* can therefore never see
      // its target sample count and blocks forever -- confirmed empirically: the naive
      // `assertReset(); waitSampling(5); deassertReset()` sequence hangs the simulator
      // indefinitely (jstack showed the ScalaTest runner thread RUNNABLE inside
      // SimVerilator.eval via SimManager.runWhile, i.e. genuinely spinning forward in
      // simulated time, never returning to Scala -- not a parked/blocked deadlock). Use
      // `sleep(ns)`, which advances raw simulation time unconditionally, to hold reset
      // asserted for a fixed duration instead.
      dut.clockDomain.assertReset()
      sleep(50)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(5)

      // Spec 15.1: init-done state "would report a previous life as the current one" if it
      // survived. A debugger attaching after a CPU reset must not be told DDR calibration
      // of the PREVIOUS boot completed, and must not inherit the previous host's forgery.
      assert(!dut.dbg.logic.csr.initDoneSticky.toBoolean,
        "the sticky init-done latch survived a CPU reset (spec 15.1: it must be wiped)")
      assert(!dut.dbg.logic.csr.ctrlInitDoneOvr.toBoolean,
        "the CONTROL bit 3 override survived a CPU reset (spec 15.1: it must be wiped)")
      assert((status() & STAT_INIT_DONE) == 0,
        "STATUS.init_done still reads set after a CPU reset -- a stale claim that DDR " +
        "calibration completed, which is exactly what spec 15.1 forbids")
      // ... while the hold, host configuration, is untouched by the same edge.
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "the cold-reset hold was wiped too -- the wipe must be selective (spec 15.1: " +
        "cold_reset_hold is explicitly NOT part of the wipe set)")
      val rb = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((rb & CTRL_COLD_HOLD) != 0, f"CONTROL readback 0x$rb%08X lost bit 4")
      assert((rb & CTRL_INIT_DONE_OVR) == 0, f"CONTROL readback 0x$rb%08X kept bit 3")
    }
  }

  test("CONTROL honours byte strobes: a zero-strobe write changes nothing") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_HOLD)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean)
      // WSTRB=0 must not clear the hold even though WDATA is zero.
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CONTROL.toLong, 0x00000000L, strb = 0x0) == 0)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "a WSTRB=0 write must leave CONTROL untouched (spec 3.1)")
      // WSTRB=0xE addresses only bytes 1..3, which hold no CONTROL bits.
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CONTROL.toLong, 0x00000000L, strb = 0xE) == 0)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "byte 0 not strobed must leave the CONTROL bits untouched")
    }
  }

  test("Stage 2 halt-after target is byte-strobed, epoch-tagged, explicitly armed, and one-shot") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)

      var invalidations = 0
      val watcher = fork {
        while (true) {
          if (dut.commitStub.logic.haltAfterInvalidate.toBoolean) invalidations += 1
          cd.waitSampling()
        }
      }

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong, 0x11223344L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong, 0x55667788L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong) == 0x11223344L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong) == 0x55667788L)
      assert(dut.commitStub.logic.haltAfterEpoch.toInt == 2,
        "each accepted target-word write must advance the stale-result epoch")
      assert(!dut.commitStub.logic.haltAfterArmed.toBoolean,
        "programming either target half must leave halt-after disarmed")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & 1L) != 0)
      assert(dut.commitStub.logic.haltAfterArmed.toBoolean)
      assert(dut.commitStub.logic.haltAfterTarget.toBigInt == BigInt("5566778811223344", 16))

      // Byte lanes 0 and 2 only: 0x11223344 -> 0x11BB33DD. Reprogramming consumes
      // the arm and advances the epoch; a zero-strobe write does neither.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong,
        0xAABBCCDDL, strb = 0x5)
      assert(DbgAxiDriver.read(dut.axi, cd,
        DebugRegMap.OFF_HALT_AFTER_LO.toLong) == 0x11BB33DDL)
      assert(dut.commitStub.logic.haltAfterEpoch.toInt == 3)
      assert(!dut.commitStub.logic.haltAfterArmed.toBoolean)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong,
        0xFFFFFFFFL, strb = 0)
      assert(dut.commitStub.logic.haltAfterEpoch.toInt == 3)
      assert(DbgAxiDriver.read(dut.axi, cd,
        DebugRegMap.OFF_HALT_AFTER_LO.toLong) == 0x11BB33DDL)

      // The ROB's automatic-halt acknowledgement consumes the arm. This prevents a
      // continue from immediately retriggering against the same absolute target.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)
      dut.commitStub.logic.haltAfterConsumedDrive #= true
      cd.waitSampling()
      dut.commitStub.logic.haltAfterConsumedDrive #= false
      cd.waitSampling()
      assert(!dut.commitStub.logic.haltAfterArmed.toBoolean)
      assert(invalidations == 3,
        s"expected one invalidate pulse per non-empty target write, got $invalidations")
      watcher.terminate()
    }
  }

  test("Stage 2 OFF_INST_LO/HI expose the ROB-owned completed-macro count") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)
      dut.commitStub.logic.macroCountDrive #= BigInt("FEDCBA9876543210", 16)
      dut.commitStub.logic.haltHitInstCountDrive #= BigInt("0123456789ABCDEF", 16)
      cd.waitSampling(2)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_INST_LO.toLong) == 0x76543210L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_INST_HI.toLong) == 0xFEDCBA98L)
      assert(DbgAxiDriver.read(dut.axi, cd,
        DebugRegMap.OFF_HALT_HIT_INST_LO.toLong) == 0x89ABCDEFL)
      assert(DbgAxiDriver.read(dut.axi, cd,
        DebugRegMap.OFF_HALT_HIT_INST_HI.toLong) == 0x01234567L)
    }
  }
}
