package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Slice 1a (design spec section 3 boundary B3, hazard H9): a completed speculative
  * install and a fresh demand miss arriving while it is pending must BOTH complete,
  * install first, and the demand must be admitted within the bounded wait.
  *
  * Before slice 1a, `lineReg`'s capture enable was `pfInstallAny && !demandFillStart`
  * -- a function of the LIVE `cmdPort.fire` verdict -- so a same-cycle demand miss
  * always won outright and no bounded-wait counter existed at all (no register lag,
  * ever). After slice 1a the install is armed from a register (`pfInstallArm`) and a
  * held demand yields to it, bounded by `installDeferMax` cycles (hazard H9,
  * `installDeferCnt`/`installStarves`) -- which, because `pfInstallArm` is always one
  * cycle behind the live `pfInstallAny` it mirrors, makes the demand ride out one
  * extra register-latency cycle beyond the pre-slice-1a scheduler's same-cycle tie
  * break every single time it is genuinely raced against an armed install. That
  * mechanistic, register-vs-combinational difference -- not "how many installs chain"
  * (hazard H9's own bound is tight enough to exhaust itself on the very first queued
  * install regardless of how much more is behind it) -- is what this test measures.
  *
  * The AXI side is driven by hand (`sendLine`/`waitReq`, mirroring the
  * `IcachePrefetchSpec` "AR-pending demotion"/"invalidation poisons" tests) rather
  * than the shared zero-latency `AxiMemModel`, for exact control over exactly when a
  * speculative line's data lands relative to the demand's own presentation.
  */
class IcachePrefetchInstallPrioritySpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new IdentityTranslationPlugin
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  test("a completed install and a same-cycle demand miss both complete, install first, within the bounded wait",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd  = dut.clockDomain
      val axi = dut.icache.logic.axi
      cd.forkStimulus(10)

      // Two DIFFERENT sets so the install is not simply the line the demand wants:
      // streamBase's window occupies sets 0..4; demandPa's set (16) is untouched by
      // it, so the ONLY thing that can ever hold the demand is an armed install.
      val streamBase = 0x0000_2000L
      val demandPa   = streamBase + 0x400L
      require(((streamBase >> 6) & 0x3f) != ((demandPa >> 6) & 0x3f),
        "test setup bug: demandPa must land in a different cache set than streamBase's window")

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0

      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
      }

      def beat(address: Long, phase: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(address + phase * 32L + i)) << (8 * i))
        }
      def sendLine(id: Int, address: Long): Unit = {
        for (phase <- 0 to 1) {
          axi.r.valid #= true
          axi.r.payload.id #= id
          axi.r.payload.data #= beat(address, phase)
          axi.r.payload.resp #= 0
          axi.r.payload.last #= (phase == 1)
          cd.waitSamplingWhere(axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }
      def waitReq(id: Int, address: Long): Unit =
        while (!arTrace.contains((id, address))) cd.waitSampling()

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Continuous, passive observation from a single fork (never poked into by the
      // main thread, so its samples are never entangled with the exact simulated-
      // delta ordering of the main thread's own `#=` pokes -- an earlier version of
      // this test tried to read state combinationally right after presenting the
      // demand in the SAME simulated instant and got inconsistent/stale reads for
      // exactly that reason; every measurement here instead comes from this one
      // free-running, clock-synchronous loop).
      var installSeen = 0   // total predIsPf-high samples, whole test (non-vacuity)
      var maxDefer     = 0   // peak installDeferCnt observed (the discriminating signal)
      val installFork = fork {
        while (true) {
          cd.waitSampling()
          if (dut.icache.logic.predIsPf.toBoolean) installSeen += 1
          val d = dut.icache.logic.installDeferCnt.toInt
          if (d > maxDefer) maxDefer = d
        }
      }

      // Phase 1: a demand miss at streamBase opens the standard five-line window --
      // its own line (ID_DEMAND) plus four speculative candidates (I_SPEC_BASE..LAST)
      // at +0x40/+0x80/+0xC0/+0x100. Resolve the demand's OWN line immediately so the
      // FSM returns to IDLE with all four speculative fills outstanding.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= streamBase
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitReq(AxiIds.I_DEMAND, streamBase)
      sendLine(AxiIds.I_DEMAND, streamBase)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(streamBase),
        "streamBase demand response data mismatch")

      for (k <- 0 to 3) waitReq(AxiIds.I_SPEC_BASE + k, streamBase + 0x40L * (k + 1))

      // Phase 2: complete ONE speculative line, then -- with no artificial gap --
      // present the cold, wholly unrelated demand while that install is in flight,
      // so it genuinely races an ARMED install rather than finding the machine idle.
      sendLine(AxiIds.I_SPEC_BASE, streamBase + 0x40L)

      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= demandPa
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitReq(AxiIds.I_DEMAND, demandPa)
      sendLine(AxiIds.I_DEMAND, demandPa)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      val demandRspPc   = dut.probe.logic.rspOut.payload.pc.toLong
      val demandRspData = dut.probe.logic.rspOut.payload.data.toBigInt

      // Drain the remaining three speculative lines so the DUT is left quiescent.
      for (k <- 1 to 3) sendLine(AxiIds.I_SPEC_BASE + k, streamBase + 0x40L * (k + 1))
      cd.waitSampling(20)   // let any trailing installs settle
      installFork.terminate()

      // GC20 non-vacuity: exact counts/PCs, not merely "some activity happened". All
      // four speculative candidates from the streamBase window install exactly once
      // each (2-cycle PF_PRED dwell) -> exactly 8 predIsPf-high samples total.
      assert(installSeen == 8,
        s"expected exactly 4 completed slots x 2-cycle PF_PRED dwell = 8 predIsPf-high " +
          s"samples over the whole test, got $installSeen")
      assert(demandRspPc == demandPa, s"demand miss lost or mis-attributed: 0x${demandRspPc.toHexString}")
      assert(demandRspData == IcacheSim.window64(demandPa), "demand miss response data mismatch")
      // The RTL's own H9 oracle (in-RTL `assert`) already proves `installDeferCnt`
      // never exceeds `installDeferMax+1`=5 -- it would have aborted the whole
      // simulation otherwise -- so the upper bound here is a belt-and-braces
      // restatement of the same guarantee from the testbench side.
      //
      // THE mutation-discriminating assertion: `pfInstallArm` arms the IDLE decision
      // cycle, and the H9 counter also counts the two PF_PRED cycles that follow it
      // (see the RTL's own counter-update comment) -- three cycles total for a
      // demand that genuinely raced a single armed install to a full stop, which is
      // exactly `maxDefer` here. Reverting slice 1a restores the pre-slice-1a
      // same-cycle combinational tie-break (`pfInstallAny && !demandFillStart`,
      // decided in the very same `lookupTick` call that admits the demand): the
      // demand wins the FIRST genuinely-idle cycle outright instead of yielding to
      // the still-queued install, so it is admitted DURING that install's own
      // PF_PRED window rather than after it -- one cycle short of the full 3.
      assert(maxDefer == 3,
        s"H9 bounded-wait was not genuinely exercised to its full peak: maxDefer=$maxDefer " +
          s"(expected exactly 3: the IDLE arm-decision cycle plus the install's own " +
          s"2-cycle PF_PRED dwell). Topping out short of that is exactly what reverting " +
          s"slice 1a's `answerable`/`when(pfInstallArm)` change (the demand-always-wins, " +
          s"same-cycle-tie-break `pfInstallAny && !demandFillStart` scheduler) produces: " +
          s"the demand wins the tie and is admitted DURING the install's PF_PRED window " +
          s"instead of yielding until after it.")
    }
  }
}
