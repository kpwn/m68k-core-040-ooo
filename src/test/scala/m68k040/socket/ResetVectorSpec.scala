package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Reset/boot (section 6)":
  *   - "SSP and PC land from bytes 0-3 / 4-7 of the vector line, big-endian."
  *   - "No fetch occurs before the redirect; the first fetch is at the loaded PC."
  *   - "A non-OKAY vector response halts with the D15 reason code on D28's channel and does
  *      not attempt a frame."
  *   - "enable=false leaves every existing test bit-identical."
  * The last of those is a whole-build property and is checked in Tasks 13/14, not here. */
class ResetVectorSpec extends AnyFunSuite {

  class FsmDut extends Component {
    val f = new ResetVectorFsm(128)
    val io = new Bundle {
      val arValid = out Bool ()
      val arAddr  = out UInt (32 bits)
      val arReady = in  Bool ()
      val rValid  = in  Bool ()
      val rData   = in  Bits (128 bits)
      val rResp   = in  Bits (2 bits)
      val rReady  = out Bool ()
      // Part 100/101 fix: RegFilePlugin.initDone gate. Driven True throughout by every
      // test in this file -- this spec exercises the FSM's own state machine given
      // correct AXI input data, not the regfile-sweep interaction (that lives in
      // ResetVectorA7RaceSpec), so the gate is left permanently open here except in the
      // one test added specifically to cover it below.
      val regInitDone   = in  Bool ()
      val sspWriteValid = out Bool ()
      val sspData       = out UInt (32 bits)
      val redirectValid = out Bool ()
      val redirectPc    = out UInt (32 bits)
      val haltPulse     = out Bool ()
    }
    io.arValid := f.io.arValid;  io.arAddr := f.io.arAddr;  f.io.arReady := io.arReady
    f.io.rValid := io.rValid;    f.io.rData := io.rData;    f.io.rResp := io.rResp
    f.io.regInitDone := io.regInitDone
    io.rReady := f.io.rReady
    io.sspWriteValid := f.io.sspWriteValid; io.sspData := f.io.sspData
    io.redirectValid := f.io.redirectValid; io.redirectPc := f.io.redirectPc
    io.haltPulse := f.io.haltPulse
  }

  /** A 16-byte line in the CORE's convention: byte i at bits [8i +: 8]. */
  private def line(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  test("D12: one 16-byte read at physical 0; SSP from bytes 0-3, PC from 4-7, big-endian") {
    SimConfig.compile(new FsmDut).doSim("boot-ok", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.arReady #= false; dut.io.rValid #= false
      dut.io.rData #= 0; dut.io.rResp #= 0
      dut.io.regInitDone #= true // gate open throughout: this test is about D12/D14, not the sweep race
      dut.clockDomain.waitSampling(3)
      // It asks, once, at address 0.
      assert(dut.io.arValid.toBoolean, "no AR presented out of reset")
      assert(dut.io.arAddr.toLong == 0L, s"AR at 0x${dut.io.arAddr.toLong.toHexString}, wanted 0")
      dut.io.arReady #= true
      dut.clockDomain.waitSampling()
      dut.io.arReady #= false
      dut.clockDomain.waitSampling(3)
      assert(!dut.io.arValid.toBoolean, "AR re-presented after it was accepted")
      // Vector line: SSP = 0x00042000, PC = 0x00004000, both big-endian in memory.
      val bytes = Seq(0x00, 0x04, 0x20, 0x00,  0x00, 0x00, 0x40, 0x00) ++ Seq.fill(8)(0xEE)
      dut.io.rValid #= true; dut.io.rData #= line(bytes); dut.io.rResp #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= false
      // D14 ordering: SSP write in cycle N, redirect in cycle N+1. Never the same cycle.
      var sawSsp = -1; var sawRedir = -1; var sspVal = BigInt(0); var pcVal = BigInt(0)
      for (c <- 0 until 12) {
        if (dut.io.sspWriteValid.toBoolean && sawSsp < 0) { sawSsp = c; sspVal = dut.io.sspData.toBigInt }
        if (dut.io.redirectValid.toBoolean && sawRedir < 0) { sawRedir = c; pcVal = dut.io.redirectPc.toBigInt }
        assert(!(dut.io.sspWriteValid.toBoolean && dut.io.redirectValid.toBoolean),
          "SSP write and redirect asserted on the SAME cycle -- D14 requires N then N+1")
        dut.clockDomain.waitSampling()
      }
      assert(sawSsp >= 0, "the SSP write never pulsed")
      assert(sawRedir == sawSsp + 1, s"redirect at cycle $sawRedir, SSP at $sawSsp -- want +1")
      assert(sspVal == BigInt(0x00042000), f"SSP 0x$sspVal%08X, wanted 0x00042000")
      assert(pcVal  == BigInt(0x00004000), f"PC  0x$pcVal%08X, wanted 0x00004000")
      assert(!dut.io.haltPulse.toBoolean, "halted on a clean boot")
    }
  }

  test("D15: a non-OKAY vector response halts and never attempts a frame") {
    for (resp <- Seq(2, 3)) {                    // SLVERR, DECERR
      SimConfig.compile(new FsmDut).doSim(s"boot-err-$resp", seed = 2) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.arReady #= true; dut.io.rValid #= false; dut.io.rData #= 0; dut.io.rResp #= 0
        dut.io.regInitDone #= true // gate open: this test is about D15's halt path, not the sweep race
        dut.clockDomain.waitSamplingWhere(dut.io.arValid.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.arReady #= false
        dut.io.rValid #= true; dut.io.rResp #= resp; dut.io.rData #= line(Seq.fill(16)(0x5A))
        // NOTE: deliberately NOT `waitSamplingWhere(dut.io.rReady.toBoolean)` here -- the
        // FSM's WAIT state drives io.rReady combinationally True for the whole cycle
        // (independent of rValid), so it is already True the instant rValid is poked above,
        // before any clock edge. waitSamplingWhere returns on an already-true condition
        // without crossing an edge (the documented "waitSamplingWhere consumes combinational
        // pulses" sim-poke gotcha), which would then let the FIRST real edge -- the exact
        // one where haltPulse pulses combinationally alongside the accept handshake, per the
        // D15 WAIT-state code path (unlike the D12 success path, whose visible pulses live a
        // full clean cycle later in APPLY0/APPLY1) -- happen inside the *next* explicit
        // waitSampling() call, before the polling loop below ever starts watching haltPulse.
        // Polling starts right here instead, at the poke itself, so the loop's very first
        // (pre-edge) check observes the live combinational pulse.
        var halted = false
        for (c <- 0 until 12) {
          if (dut.io.haltPulse.toBoolean) halted = true
          assert(!dut.io.sspWriteValid.toBoolean, s"resp=$resp wrote an SSP from a failed fetch")
          assert(!dut.io.redirectValid.toBoolean, s"resp=$resp redirected off a failed fetch")
          dut.clockDomain.waitSampling()
          if (c == 0) dut.io.rValid #= false
        }
        assert(halted, s"resp=$resp did not halt")
      }
    }
  }

  test("D12: DONE is terminal -- it never re-arms without a core reset") {
    SimConfig.compile(new FsmDut).doSim("terminal", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.arReady #= true; dut.io.rValid #= false; dut.io.rData #= 0; dut.io.rResp #= 0
      dut.io.regInitDone #= true // gate open: this test is about DONE terminality, not the sweep race
      dut.clockDomain.waitSamplingWhere(dut.io.arValid.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= true
      dut.io.rData #= line(Seq(0,0,0x10,0, 0,0,0x20,0) ++ Seq.fill(8)(0))
      dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= false
      dut.clockDomain.waitSampling(40)
      for (_ <- 0 until 40) {
        assert(!dut.io.arValid.toBoolean, "the reset-vector reader re-armed")
        assert(!dut.io.sspWriteValid.toBoolean, "a second SSP write")
        assert(!dut.io.redirectValid.toBoolean, "a second redirect")
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("Part 100/101 fix: the FSM itself parks in SWEEP_WAIT (issues neither the SSP " +
       "write nor the redirect) while regInitDone is low, and only proceeds once it goes " +
       "high, without losing sspReg/pcReg while parked") {
    SimConfig.compile(new FsmDut).doSim("sweep-wait-gate", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.arReady #= true; dut.io.rValid #= false; dut.io.rData #= 0; dut.io.rResp #= 0
      dut.io.regInitDone #= false // gate CLOSED: the whole point of this test
      dut.clockDomain.waitSamplingWhere(dut.io.arValid.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.arReady #= false
      val bytes = Seq(0x00, 0x04, 0x20, 0x00,  0x00, 0x00, 0x40, 0x00) ++ Seq.fill(8)(0xEE)
      dut.io.rValid #= true; dut.io.rData #= line(bytes); dut.io.rResp #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= false

      // Hold the gate closed for a while (well past what the D12 test's 12-cycle poll
      // window would need if the gate did nothing) -- the FSM must sit parked, issuing
      // neither pulse, the whole time.
      for (_ <- 0 until 30) {
        assert(!dut.io.sspWriteValid.toBoolean, "SSP write issued while regInitDone was low")
        assert(!dut.io.redirectValid.toBoolean, "redirect issued while regInitDone was low")
        dut.clockDomain.waitSampling()
      }

      // Open the gate -- the FSM should fall through to APPLY0/APPLY1 with the ORIGINAL
      // latched vector data, unchanged by having been parked.
      dut.io.regInitDone #= true
      var sawSsp = -1; var sawRedir = -1; var sspVal = BigInt(0); var pcVal = BigInt(0)
      for (c <- 0 until 12) {
        if (dut.io.sspWriteValid.toBoolean && sawSsp < 0) { sawSsp = c; sspVal = dut.io.sspData.toBigInt }
        if (dut.io.redirectValid.toBoolean && sawRedir < 0) { sawRedir = c; pcVal = dut.io.redirectPc.toBigInt }
        dut.clockDomain.waitSampling()
      }
      assert(sawSsp >= 0, "the SSP write never pulsed after the gate opened")
      assert(sawRedir == sawSsp + 1, s"redirect at cycle $sawRedir, SSP at $sawSsp -- want +1 (D14 preserved)")
      assert(sspVal == BigInt(0x00042000), f"SSP 0x$sspVal%08X, wanted 0x00042000 (parking must not corrupt it)")
      assert(pcVal  == BigInt(0x00004000), f"PC  0x$pcVal%08X, wanted 0x00004000 (parking must not corrupt it)")
    }
  }
}
