package m68k040.socket

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.{FetchAlignPlugin, DecodeFeedProbePlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Real-hardware bug hunt (2026-09-03, docs/BUG_calibration_word_misplaced_0d00.md Part 94):
  * does the CORE's own reset-vector path (`ResetVectorFsm` -> `FetchAlignPlugin.resetRedirect`
  * -> `IcachePlugin` -> the first decoded fetch packet) actually work end to end, given a
  * REAL AXI response at address 0 carrying the real ROM's own SSP/PC vector line?
  *
  * This is deliberately NOT a re-test of `ResetVectorFsm` in isolation (`ResetVectorSpec`
  * already proves the FSM's own state machine is correct given correct input data) and NOT a
  * re-test of `ResetVectorPlugin`'s wiring by inspection (already read and confirmed
  * one-line-for-one-line correct). What neither of those exercises is the FULL live
  * interaction this test drives: a real `ResetVectorFsm`, wired into a real `FetchAlignPlugin`
  * exactly as `ResetVectorPlugin` wires it (same override, same allowOverride/Fiber-ordering
  * shape, same "AFTER FetchAlignPlugin" plugin-list position), talking to a real
  * `IcachePlugin` that serves the SUBSEQUENT instruction fetch at the resolved PC -- i.e. the
  * entire chain the boot-time redirect depends on inside this repo's own RTL, with the SoC's
  * `MIRROR_LOW_RAM`/crossbar-overlay layer standing in as "assume the AXI response is
  * correct", which is the one boundary this repo cannot simulate on its own (that lives in
  * the SoC's `rtl/soc/boot_fsm.v`/`axi_xbar.v`, a separate repo). */
class ResetVectorIntegrationSpec extends AnyFunSuite {

  /** Faithful stand-in for `ResetVectorPlugin`: same override, same Fiber-ordering
    * discipline ("AFTER FetchAlignPlugin", per that class's own header), same signals. The
    * FSM's own `arValid`/`arAddr`/`arReady`/`rValid`/`rData`/`rResp`/`rReady` ports are
    * re-exposed here as plain directional Area signals (not internally driven) so the
    * testbench can play the merge-arbiter/axi_d role directly, exactly like
    * `ResetVectorSpec`'s `FsmDut` does for the FSM alone. */
  class TestResetVectorPlugin extends FiberPlugin {
    val logic = during build new Area {
      val fa  = host[FetchAlignPlugin]
      val fsm = new ResetVectorFsm(128)

      // `fsm` is a genuine nested Component (unlike FetchAlignPlugin's own Area-only
      // ports), so its unconnected inputs need real drivers inside this flattened
      // hierarchy for SpinalHDL's no-driver check -- exposed here as directional signals
      // the testbench can poke directly, same shape as `ResetVectorSpec.FsmDut`'s io.
      val arValid = out(Bool());        arValid := fsm.io.arValid
      val arAddr  = out(UInt(32 bits)); arAddr  := fsm.io.arAddr
      val arReady = in(Bool());         fsm.io.arReady := arReady
      val rValid  = in(Bool());         fsm.io.rValid  := rValid
      val rData   = in(Bits(128 bits)); fsm.io.rData   := rData
      val rResp   = in(Bits(2 bits));   fsm.io.rResp   := rResp
      val rReady  = out(Bool());        rReady  := fsm.io.rReady

      fa.logic.resetRedirect.valid   := fsm.io.redirectValid
      fa.logic.resetRedirect.payload := fsm.io.redirectPc

      // Mirrors of what was just fed into `resetRedirect`, exposed as plain Area outputs
      // (rather than peeking `fa.logic.resetRedirect` itself, which SpinalHDL's Verilator
      // sim backend prunes as unaccessible without a `simPublic()` this test deliberately
      // avoids adding to production source) -- same values, same cycle, since the override
      // above is a pure same-cycle combinational assignment.
      val redirectValid = out(Bool()); redirectValid := fsm.io.redirectValid
      val redirectPc    = out(UInt(32 bits)); redirectPc := fsm.io.redirectPc
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic    = new IcachePlugin
    val fa    = new FetchAlignPlugin
    val probe = new DecodeFeedProbePlugin
    val trv   = new TestResetVectorPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, probe, trv)) }
  }

  /** A 16-byte AXI line in the FSM's own convention (matches `ResetVectorSpec.line`): byte i
    * at bits [8i +: 8]. */
  private def line(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  test("real 68040 reset behaviour end-to-end: FSM redirect -> frontend actually " +
       "fetches+decodes at the resolved low PC, not the ROM's high alias",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      // The real ROM's own reset vector (per Part 93/94's confirmed disassembly):
      // SSP = whatever (0x00042000, arbitrary-but-plausible), PC = 0x0000002A.
      val vectorPc = 0x2AL

      // Instruction memory the SUBSEQUENT instruction fetch (via IcachePlugin's AXI, the
      // `axi_i`-equivalent path in this unit-level DUT) must land on: a canary opcode
      // (MOVEQ #5,%d1 = 0x7205) at exactly 0x2A, MOVEQ #0,%d0 filler everywhere else so a
      // wrong-PC fetch would decode to something clearly NOT the canary.
      val words = Array.fill(64)(0x7000)
      words((vectorPc / 2).toInt) = 0x7205
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, 0L, words.toSeq)

      // Nothing else may redirect fetch in this DUT -- mirrors SocketTop.scala:394-395's
      // own `fa.logic.redirect.valid := False` pin for the real socket build, so the ONLY
      // live redirect source is the reset-vector FSM under test.
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.probe.logic.feedOut.ready #= false
      dut.trv.logic.arReady #= false
      dut.trv.logic.rValid  #= false
      dut.trv.logic.rData   #= 0
      dut.trv.logic.rResp   #= 0
      cd.waitSampling(3)

      // 1) Does the FSM's AR request appear at all, at physical address 0, unprompted --
      // i.e. does reset alone (no external redirect) drive the boot sequence, matching the
      // real 68040's architectural behaviour ResetVectorFsm's own header comment claims?
      assert(dut.trv.logic.arValid.toBoolean,
        "no AR request out of reset -- the reset-vector read never even starts")
      assert(dut.trv.logic.arAddr.toLong == 0L,
        s"AR at 0x${dut.trv.logic.arAddr.toLong.toHexString}, wanted physical 0")

      // 2) Play the "real ROM read" response: SSP/PC line, PC = 0x2A (the literal
      // architectural reset-vector PC Part 94 armed a real breakpoint at).
      dut.trv.logic.arReady #= true
      cd.waitSampling()
      dut.trv.logic.arReady #= false
      val bytes = Seq(0x00, 0x04, 0x20, 0x00,  0x00, 0x00, 0x00, 0x2A) ++ Seq.fill(8)(0xEE)
      dut.trv.logic.rValid #= true
      dut.trv.logic.rData  #= line(bytes)
      dut.trv.logic.rResp  #= 0
      cd.waitSamplingWhere(dut.trv.logic.rReady.toBoolean)
      cd.waitSampling()
      dut.trv.logic.rValid #= false

      // 3) Does the redirect actually reach FetchAlignPlugin and get accepted (no
      // higher-priority idle arm silently eating it)?
      var sawRedirect = false
      var redirPc = -1L
      for (_ <- 0 until 8) {
        if (dut.trv.logic.redirectValid.toBoolean) {
          sawRedirect = true
          redirPc = dut.trv.logic.redirectPc.toLong
        }
        cd.waitSampling()
      }
      assert(sawRedirect, "ResetVectorFsm.redirectValid never fired -- " +
        "the core's own reset-vector FSM, not the SoC layer, is dropping the reset redirect")
      assert(redirPc == vectorPc,
        f"redirect carried PC 0x$redirPc%08X, wanted the real reset vector 0x$vectorPc%08X")

      // 4) THE decisive check: does the frontend actually go on to fetch+decode AT that low
      // PC (real overlay-mode execution), or does something upstream silently re-route it to
      // the high ROM alias / never issue the fetch at all (matching the real-hardware
      // observation that 0x2A is never reached)?
      dut.probe.logic.feedOut.ready #= true
      var gotPacket = false
      var gotPc = -1L
      var gotSimple = false
      for (_ <- 0 until 200 if !gotPacket) {
        if (dut.probe.logic.feedOut.valid.toBoolean) {
          gotPacket = true
          gotPc     = dut.probe.logic.feedOut.payload(0).pc.toLong
          gotSimple = dut.probe.logic.feedOut.payload(0).simple.toBoolean
        }
        cd.waitSampling()
      }
      assert(gotPacket, "no decode packet ever arrived -- fetch never started at all " +
        "(started stayed False), matching real hardware's own dead-boot symptom")
      assert(gotPc == vectorPc,
        f"first decoded packet PC = 0x$gotPc%08X, wanted the reset-vector PC 0x$vectorPc%08X " +
        "-- the core redirected fetch somewhere other than the architectural reset vector")
      assert(gotSimple, "canary packet at 0x2A did not decode as the expected simple MOVEQ")
    }
  }
}
