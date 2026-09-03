package m68k040.socket

import m68k040.{M68kSim, VerilatorTest}
import m68k040.rename.{RenameStage, DecodeUopSourcePlugin, RenameUopSinkPlugin, RenameCommitDriverPlugin}
import m68k040.execute.regfile.RegFilePluginInt
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Direct sim confirmation for `docs/BUG_calibration_word_misplaced_0d00.md` Part 98
  * S6's own recommended next step: does either of the two concrete, doubly-sufficient
  * RTL mechanisms Part 98 found for the real-hardware A7/MSP/ISP == 0x00000000
  * anomaly (20-for-20 trials) actually fire, rather than merely being read-and-traced
  * as plausible?
  *
  * Mechanism A: `RegFilePlugin`'s own 50-cycle post-reset zero-sweep unconditionally
  * blocks (index >= 1 physical write ports) or overrides-with-zero (index 0) any
  * external write issued before `initDone` -- including a reset-vector-style SSP
  * write, regardless of whether its address is even correct.
  *
  * Mechanism B: `RatTable.commReg` (`RenameStage`'s int RAT committed-mapping
  * storage) has NO `.init(...)`, so it is never connected to any reset network --
  * a stale mapping from BEFORE a reset survives that reset undisturbed, until
  * `RenameStage`'s own separately-reset `initCounter`/`initDone` walk (a fixed
  * `Isa.ARCH_INT_REGS - 1 = 19`-cycle affair) gets back around to overwriting it.
  *
  * This is deliberately built as a NEW, minimal DUT (`RenameStage` + `RegFilePluginInt`
  * + the existing `m68k040.rename` test-only scaffolding plugins + this file's own
  * `A7RaceDriverPlugin`) rather than an extension of `ResetVectorIntegrationSpec`'s own
  * `Dut` -- that DUT wires `IcachePlugin`/`FetchAlignPlugin`, neither of which this
  * question needs or can drive a regfile-race investigation through; this is the
  * "sibling test" option Part 98 S6 itself named as acceptable. Same underlying
  * real components (`RegFilePluginInt`, `RenameStage`), same `CommittedMapService`
  * address-routing FullCoreSynth's real `a7Wr`/`a7Rd` ports use. */
class ResetVectorA7RaceSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    val rf   = new RegFilePluginInt
    val drv  = new A7RaceDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv, rf, drv)) }
  }

  /** Fix-verification DUT (Part 100/101 follow-up): the SAME real `RegFilePluginInt` +
    * `RenameStage` + scaffolding as `Dut` above, but driving the SSP write through a REAL
    * `ResetVectorFsm` (`FsmA7DriverPlugin`) instead of a raw manual poke -- so these tests
    * exercise the ACTUAL fixed production code path, not a re-implementation of its gate. */
  class DutFsm extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    val rf   = new RegFilePluginInt
    val fdrv = new FsmA7DriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv, rf, fdrv)) }
  }

  private def idleFsm(dut: DutFsm): Unit = {
    dut.dsrc.logic.src.valid #= false
    dut.sink.logic.out.ready #= true
    dut.cdrv.logic.flushIn #= false
    dut.cdrv.logic.cmd.foreach(_.valid #= false)
    dut.fdrv.logic.arReady #= false
    dut.fdrv.logic.rValid  #= false
    dut.fdrv.logic.rData   #= 0
    dut.fdrv.logic.rResp   #= 0
  }

  /** A 16-byte AXI line in the FSM's own convention (matches `ResetVectorSpec.line`/
    * `ResetVectorIntegrationSpec.line`): byte i at bits [8i +: 8]. */
  private def fsmLine(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  private def idle(dut: Dut): Unit = {
    dut.dsrc.logic.src.valid #= false
    dut.sink.logic.out.ready #= true
    dut.cdrv.logic.flushIn #= false
    dut.cdrv.logic.cmd.foreach(_.valid #= false)
    dut.drv.logic.useFixedAddr #= false
    dut.drv.logic.fixedAddr #= 0
    dut.drv.logic.rdUseFixedAddr #= false
    dut.drv.logic.rdFixedAddr #= 0
    dut.drv.logic.wrValid #= false
    dut.drv.logic.wrData #= 0
  }

  private val sspVal = BigInt("00042000", 16)

  test("Mechanism A: a write issued before RegFilePlugin.initDone is silently dropped, " +
       "even when the address is already architecturally correct", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut)
      dut.drv.logic.useFixedAddr #= true;   dut.drv.logic.fixedAddr #= 15
      dut.drv.logic.rdUseFixedAddr #= true; dut.drv.logic.rdFixedAddr #= 15
      cd.waitSampling(2)

      assert(!dut.drv.logic.regInitDone.toBoolean,
        "test-setup invariant violated: the zero-sweep must still be running at this point")

      // Fire the SSP write for exactly 1 cycle -- ResetVectorFsm.APPLY0's own shape --
      // well within the sweep, at the CORRECT phys id (isolating Mechanism A alone).
      dut.drv.logic.wrValid #= true; dut.drv.logic.wrData #= sspVal
      cd.waitSampling()
      dut.drv.logic.wrValid #= false

      // Wait out the rest of the sweep (depth 50) with a healthy margin.
      cd.waitSampling(90)
      assert(dut.drv.logic.regInitDone.toBoolean, "sweep should be complete by now")

      val readback = dut.drv.logic.rdData.toBigInt
      assert(readback == 0,
        f"expected the SSP write to have been silently dropped (readback should be the " +
        f"sweep's own final zero, 0x00000000) but got 0x$readback%08X -- Mechanism A did " +
        f"NOT reproduce")
    }
  }

  test("control: a write issued AFTER RegFilePlugin.initDone lands correctly " +
       "(validates the harness itself, contrasting the Mechanism-A failure above)",
       VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut)
      dut.drv.logic.useFixedAddr #= true;   dut.drv.logic.fixedAddr #= 15
      dut.drv.logic.rdUseFixedAddr #= true; dut.drv.logic.rdFixedAddr #= 15
      cd.waitSampling(90)
      assert(dut.drv.logic.regInitDone.toBoolean)

      dut.drv.logic.wrValid #= true; dut.drv.logic.wrData #= sspVal
      cd.waitSampling()
      dut.drv.logic.wrValid #= false
      cd.waitSampling(2)

      val readback = dut.drv.logic.rdData.toBigInt
      assert(readback == sspVal,
        f"post-sweep write should land untouched: got 0x$readback%08X, wanted 0x$sspVal%08X")
    }
  }

  test("Mechanism B: RatTable.commReg has no reset -- a stale committed mapping from " +
       "before a reset survives that reset, until the RAT's own re-walk overwrites it",
       VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut)
      dut.drv.logic.rdUseFixedAddr #= true; dut.drv.logic.rdFixedAddr #= 0 // unused in this test
      // Let reset actually propagate before sampling anything -- at simulation time 0
      // (before the first clock edge / before reset has taken hold) every register,
      // including `ratInitDone` itself, can read back arbitrary per-seed X-derived
      // garbage; this settle delay is what every OTHER test in this file already does
      // via `cd.waitSampling(2)` right after `idle(dut)` (observed directly: omitting
      // it here made this test flaky, catching `ratInitDone` apparently already true --
      // and `committedPhysA7Out` reading random garbage -- at n=0/simTime=0, before
      // reset had run at all; a test-harness bug, not a DUT finding).
      cd.waitSampling(2)

      // 1) Let the FIRST power-on's RAT walk complete (arch 15 -> phys 15, identity).
      var n = 0
      while (!dut.drv.logic.ratInitDone.toBoolean && n < 60) { cd.waitSampling(); n += 1 }
      assert(dut.drv.logic.ratInitDone.toBoolean, "RAT init never completed")
      assert(dut.drv.logic.committedPhysA7Out.toBigInt == 15,
        "sanity: identity mapping expected after the first init walk")

      // 2) Simulate what a REAL prior power cycle's execution could leave behind:
      // commit arch A7 (15) to a DIFFERENT physical register (e.g. an OoO
      // `move ...,%sp` that ran and retired before that earlier power cycle ended).
      val staleTarget = 33
      dut.cdrv.logic.cmd(0).valid #= true
      dut.cdrv.logic.cmd(0).payload.intArch   #= 15
      dut.cdrv.logic.cmd(0).payload.intNew    #= staleTarget
      dut.cdrv.logic.cmd(0).payload.intOld    #= 15
      dut.cdrv.logic.cmd(0).payload.intWrite  #= true
      dut.cdrv.logic.cmd(0).payload.nzvcWrite #= false
      dut.cdrv.logic.cmd(0).payload.xWrite    #= false
      cd.waitSampling()
      dut.cdrv.logic.cmd(0).valid #= false
      cd.waitSampling(2)
      assert(dut.drv.logic.committedPhysA7Out.toBigInt == staleTarget,
        "commit didn't take -- test-setup bug, not the RTL under test")

      // 3) A SECOND reset (a real CPU/JTAG reset, NOT a power cycle): everything with
      // an `.init(...)` (RenameStage's initDone/initCounter) resets; RatTable.commReg,
      // with no `.init(...)`, does not.
      cd.assertReset()
      sleep(6 * 10)
      cd.deassertReset()
      cd.waitSampling(1)

      // 4) THE decisive check: sample committedPhysA7 immediately after reset release,
      // before the RAT's re-walk has any chance to reach arch index 15 (needs ~19-20
      // cycles). If Mechanism B is real, this still reads the STALE `staleTarget`.
      assert(!dut.drv.logic.ratInitDone.toBoolean, "sanity: RAT init should have restarted")
      val postResetPhys = dut.drv.logic.committedPhysA7Out.toBigInt
      assert(postResetPhys == staleTarget,
        s"expected the stale pre-reset mapping ($staleTarget) to survive the reset " +
        s"(RatTable.commReg has no .init and should not have been cleared), but got " +
        s"$postResetPhys -- Mechanism B did NOT reproduce")

      // 5) The walk DOES eventually correct it -- confirm the window actually closes,
      // so this is characterised as a race window, not a permanent corruption.
      n = 0
      while (!dut.drv.logic.ratInitDone.toBoolean && n < 60) { cd.waitSampling(); n += 1 }
      assert(dut.drv.logic.ratInitDone.toBoolean)
      assert(dut.drv.logic.committedPhysA7Out.toBigInt == 15,
        "RAT walk should have restored identity by the time initDone re-asserts")
    }
  }

  test("adjudication: on an ordinary combined reset, the RAT's own init walk finishes " +
       "strictly BEFORE RegFilePlugin's zero-sweep -- Mechanism A's window is a strict " +
       "superset of Mechanism B's, so B cannot be independently sufficient here",
       VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut)
      cd.waitSampling(2) // let reset actually propagate before sampling either initDone flag

      var ratCycle = -1; var regCycle = -1
      var n = 0
      while ((ratCycle < 0 || regCycle < 0) && n < 200) {
        if (ratCycle < 0 && dut.drv.logic.ratInitDone.toBoolean) ratCycle = n
        if (regCycle < 0 && dut.drv.logic.regInitDone.toBoolean) regCycle = n
        cd.waitSampling(); n += 1
      }
      assert(ratCycle >= 0 && regCycle >= 0, "one of the two sweeps never completed within 200 cycles")
      assert(ratCycle < regCycle,
        s"expected the RAT identity walk to finish before the PRF zero-sweep " +
        s"(rat=$ratCycle cycles, reg=$regCycle cycles) -- if this ever flips, Mechanism B " +
        s"could become independently sufficient on its own, not merely compounding")
    }
  }

  // NOTE (Part 100/101 follow-up, fix landed): this test's driver is a RAW manual poke of
  // A7RaceDriverPlugin.wr -- it does NOT go through ResetVectorFsm, so it does NOT exercise
  // the Part 101 fix (which lives entirely inside ResetVectorFsm's own SWEEP_WAIT gate, not
  // in RegFilePlugin). It is kept, UNCHANGED, as a regression guard documenting that
  // RegFilePlugin's zero-sweep still silently drops an early write that bypasses the FSM's
  // gate -- deliberately, per the Part 100/101 fix-shape decision (option 1: gate the FSM,
  // not the shared regfile every other write port also depends on). The REAL, FIXED,
  // end-to-end production path -- driven through an actual `ResetVectorFsm` gated on the
  // real `RegFilePlugin.initDone` -- is the new test below this one, which now passes with
  // A7 correctly holding the SSP value instead of 0x00000000.
  test("end-to-end (PRE-FIX MECHANISM, regression guard): a reset-vector-style SSP write " +
       "racing both windows via a RAW manual poke that bypasses ResetVectorFsm's gate, " +
       "addressed the SAME way FullCoreSynth's real a7Wr/a7Rd are (via committedPhysA7, " +
       "not a fixed constant), leaves A7 reading exactly 0x00000000 -- matching the real-" +
       "hardware 20-for-20 anomaly's underlying mechanism (still real; NOT what the fix " +
       "touches -- see the FIXED end-to-end test below)", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut) // useFixedAddr/rdUseFixedAddr both False: address tracks committedPhysA7 live
      cd.waitSampling(2)

      // Fire the write EARLY -- mirrors ResetVectorFsm.APPLY0, which can plausibly land
      // just a handful of cycles after reset release if the AXI round trip is fast.
      // (Part 98 flagged real AXI latency as an unverified premise; this picks a
      // deliberately-fast, plausible cycle count to probe the race window itself, not
      // a specific measured hardware AXI latency.)
      dut.drv.logic.wrValid #= true; dut.drv.logic.wrData #= sspVal
      cd.waitSampling()
      dut.drv.logic.wrValid #= false

      cd.waitSampling(90)
      assert(dut.drv.logic.regInitDone.toBoolean)
      assert(dut.drv.logic.ratInitDone.toBoolean)
      assert(dut.drv.logic.committedPhysA7Out.toBigInt == 15,
        "sanity: once both sweeps are done, A7 should be back to identity")

      val a7 = dut.drv.logic.rdData.toBigInt
      assert(a7 == 0,
        f"A7 readback should be exactly 0x00000000 (Mechanism A dropped the SSP write) " +
        f"matching the real-hardware 20-for-20 anomaly, but got 0x$a7%08X")
    }
  }

  test("Part 100/101 FIX VERIFIED, end-to-end: driven through a REAL ResetVectorFsm " +
       "(SWEEP_WAIT gate) wired to a REAL RegFilePlugin.initDone -- an early AXI response " +
       "no longer loses the SSP write; A7 correctly holds the reset vector's SSP value, " +
       "NOT 0x00000000, once both the FSM and the zero-sweep have settled", VerilatorTest) {
    M68kSim().compile(new DutFsm).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idleFsm(dut)
      cd.waitSampling(2)

      assert(!dut.fdrv.logic.regInitDone.toBoolean,
        "test-setup invariant violated: the zero-sweep must still be running at this point")

      // Present the reset-vector AXI response EARLY -- deep inside the 50-cycle sweep
      // window, the same "plausible fast AXI round trip" premise Part 98/100's own tests
      // probe (real AXI latency being an unverified premise either way -- this exercises
      // the race window itself, not a specific measured hardware latency).
      dut.fdrv.logic.arReady #= true
      cd.waitSamplingWhere(dut.fdrv.logic.arValid.toBoolean)
      cd.waitSampling()
      dut.fdrv.logic.arReady #= false

      val bytes = Seq(0x00, 0x04, 0x20, 0x00,  0x00, 0x00, 0x40, 0x00) ++ Seq.fill(8)(0xEE)
      dut.fdrv.logic.rValid #= true
      dut.fdrv.logic.rData  #= fsmLine(bytes)
      dut.fdrv.logic.rResp  #= 0
      cd.waitSamplingWhere(dut.fdrv.logic.rReady.toBoolean)
      cd.waitSampling()
      dut.fdrv.logic.rValid #= false

      assert(!dut.fdrv.logic.regInitDone.toBoolean,
        "test-setup invariant violated: the AXI response landed AFTER the sweep finished " +
        "-- the race window this test targets was never actually open")

      // Track, cycle-by-cycle, when the sweep completes, when the SSP write fires, and
      // when the redirect fires -- the DECISIVE property is that the write cannot fire
      // before the sweep completes (that is the whole fix), and D14's write-then-redirect
      // ordering must still hold once it does.
      var doneCycle = -1; var writeCycle = -1; var redirCycle = -1; var writeVal = BigInt(0)
      var n = 0
      while ((doneCycle < 0 || writeCycle < 0 || redirCycle < 0) && n < 120) {
        if (doneCycle < 0 && dut.fdrv.logic.regInitDone.toBoolean) doneCycle = n
        if (writeCycle < 0 && dut.fdrv.logic.sspWriteValid.toBoolean) {
          writeCycle = n; writeVal = dut.fdrv.logic.sspDataOut.toBigInt
        }
        if (redirCycle < 0 && dut.fdrv.logic.redirectValid.toBoolean) redirCycle = n
        cd.waitSampling(); n += 1
      }
      assert(doneCycle >= 0, "the zero-sweep never completed within 120 cycles")
      assert(writeCycle >= 0, "the SSP write never fired -- the FSM never left SWEEP_WAIT")
      assert(redirCycle >= 0, "the redirect never fired")
      assert(writeCycle >= doneCycle,
        s"FIX VIOLATED: the SSP write fired at cycle $writeCycle, BEFORE the sweep " +
        s"completed at cycle $doneCycle -- ResetVectorFsm issued its write while the " +
        s"zero-sweep could still silently drop it")
      assert(redirCycle == writeCycle + 1,
        s"D14 ordering broken by the fix: redirect at $redirCycle, SSP write at " +
        s"$writeCycle -- want exactly +1")
      assert(writeVal == sspVal, f"SSP write carried 0x$writeVal%08X, wanted 0x$sspVal%08X")

      // Let the write's own 1-cycle latency land, then read A7 back through the SAME
      // committedPhysA7-addressed read path FullCoreSynth's real a7Rd uses.
      cd.waitSampling(5)
      assert(dut.fdrv.logic.committedPhysA7Out.toBigInt == 15,
        "sanity: once both sweeps are done, A7 should be back to identity")
      val a7 = dut.fdrv.logic.rdData.toBigInt
      assert(a7 == sspVal,
        f"FIX VERIFICATION FAILED: A7 should hold the reset vector's SSP value " +
        f"(0x$sspVal%08X) now that the write is correctly held until after the sweep, " +
        f"but got 0x$a7%08X")
    }
  }
}
