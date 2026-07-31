package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** Proves `AxiMemModelConfig.crossbarSingleOutstanding` genuinely restricts concurrency
  * to the real `macqd700-soc/rtl/soc/axi_xbar.v` shape (`AxiMemModel.scala`'s
  * `crossbarSingleOutstanding` doc comment), and -- the more important half -- that the
  * model can express real concurrency when crossbar mode is OFF. Without the negative
  * test, the design doc's §3.2 claim that "multi-outstanding buys nothing on today's
  * SoC" would be un-measurable: a model that just NEVER goes concurrent (crossbar mode
  * or not) would make `todaysCrossbar` an accidentally-true assertion rather than a
  * demonstrated one. Reuses `MultiIdReadDut` from `AxiMemModelSpec` (same package). */
class CrossbarModeSpec extends AnyFunSuite {

  test("crossbarSingleOutstanding admits at most one concurrent read") {
    val dut = SimConfig.compile(new MultiIdReadDut(4))
    dut.doSim("xbar", seed = 11) { d =>
      d.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(d.io.axi, d.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      d.io.go #= true
      d.clockDomain.waitSampling(1200)
      assert(m.stats.maxConcurrentReads <= 1,
        s"crossbar mode allowed ${m.stats.maxConcurrentReads} concurrent reads")
      assert(d.io.done.toInt == 0xf, "not all four reads completed under the crossbar model")
    }
  }

  test("WITHOUT crossbar mode, four distinct IDs ARE concurrent") {
    val dut = SimConfig.compile(new MultiIdReadDut(4))
    dut.doSim("noxbar", seed = 11) { d =>
      d.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(d.io.axi, d.clockDomain,
        AxiMemModelConfig(maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      d.io.go #= true
      d.clockDomain.waitSampling(1200)
      assert(m.stats.maxConcurrentReads > 1,
        "the model never allowed concurrency even with the crossbar model OFF -- " +
        "the 'multi-outstanding buys nothing on today's SoC' claim cannot be measured")
    }
  }
}
