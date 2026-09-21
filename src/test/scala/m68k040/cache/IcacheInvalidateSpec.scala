package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Slice I1, task I1.4 — closure of the `invalidateAll` hazard (MSHR design doc §6.5).
  *
  * The hazard had TWO halves and only one was closed before this slice:
  *
  *  (a) the fill's `valids(w)(set) := True` elaborates AFTER the priority clear, so
  *      under SpinalHDL's last-assignment-wins a SIMULTANEOUS invalidate lost. Closed
  *      earlier (task P5.5) and covered by `IcacheSpec`'s own race test.
  *  (b) an invalidate that lands ONE OR MORE CYCLES EARLIER, while the burst is still
  *      on the bus, was not handled at all: the fill would complete and re-install the
  *      very line the invalidate was told to drop. That is what `missPoison` closes,
  *      and what this file tests. It was previously UNREACHABLE (the port had no real
  *      driver) and therefore untested; slice I3's prefetch fills widen the window
  *      further, and the copyback design's real CINV/CPUSH makes it live.
  *
  * The third test is the important one and guards a closure that was deliberately NOT
  * adopted: §6.5 also proposes invalidating the in-flight `s1*`/`rsp*` stages. Doing
  * that would strand a `FetchAlignPlugin` ring entry forever (`ringCount` is decremented
  * ONLY by `ic.rsp.valid`, with no timeout and no other path), eventually blocking
  * `ic.cmd.valid` permanently — a hard front-end hang CREATED by the fix. So the
  * response is always delivered, and this test pins that.
  */
class IcacheInvalidateSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new IdentityTranslationPlugin
    val icache = new IcachePlugin(IcachePredecodeConfig.fromEnvironment)
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  def simConfig = SimConfig.withVerilator

  private def arCount(dut: Dut, cd: ClockDomain): () => Int = {
    var n = 0
    cd.onSamplings {
      if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) n += 1
    }
    () => n
  }

  test("invalidateAll DURING a refill burst leaves NO line valid for that set", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      val pc  = 0x1040L
      val set = ((pc >> 6) & 0x3f).toInt

      // Start the fetch, then pulse invalidateAll as soon as the AR has gone out --
      // i.e. mid-burst, strictly BEFORE the allocation cycle. This is the window that
      // `missPoison` closes and that the same-cycle guard alone does nothing for.
      val racer = fork {
        cd.waitSamplingWhere(dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean)
        dut.icache.logic.invalidateAll #= true
        cd.waitSampling()
        dut.icache.logic.invalidateAll #= false
      }

      val got = fetch(dut, cd, pc)
      racer.join()
      cd.waitSampling(8)

      // The in-flight fetch still gets its correct bytes -- the data was what was at
      // that address when the fetch was issued (see test 3 for why it MUST be answered).
      assert(got == IcacheSim.window64(pc),
        s"poisoned refill must still deliver correct data: got 0x${got.toString(16)}")

      // THE ASSERTION: nothing may be left resident in that set. Pre-slice this read as
      // valid -- the fill completed and installed the line the invalidate had cleared.
      assert(!(0 until 4).exists(w => dut.icache.logic.valids(w)(set).toBoolean),
        s"a refill poisoned by a mid-burst invalidateAll left set $set resident")
    }
  }

  test("a fetch AFTER a poisoned refill re-misses and issues a NEW AR", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ars = arCount(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      val pc = 0x1040L
      val racer = fork {
        cd.waitSamplingWhere(dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean)
        dut.icache.logic.invalidateAll #= true
        cd.waitSampling()
        dut.icache.logic.invalidateAll #= false
      }
      fetch(dut, cd, pc)
      racer.join()
      cd.waitSampling(8)

      val before = ars()
      val got = fetch(dut, cd, pc)
      cd.waitSampling(8)
      assert(got == IcacheSim.window64(pc), "re-fetch after a poisoned refill must be correct")
      assert(ars() == before + 1,
        s"a poisoned refill allocated nothing, so the next fetch must issue a NEW real AR: $before -> ${ars()}")
    }
  }

  test("invalidateAll during a refill still DELIVERS the in-flight response (no ring wedge)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      // Count rsp pulses for exactly one issued fetch. `FetchAlignPlugin` decrements its
      // outstanding-ring count ONLY here, so the count must be exactly 1 -- zero wedges
      // the front end forever, and two would double-retire.
      var rsps = 0
      cd.onSamplings { if (dut.probe.logic.rspOut.valid.toBoolean) rsps += 1 }

      val racer = fork {
        cd.waitSamplingWhere(dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean)
        dut.icache.logic.invalidateAll #= true
        cd.waitSampling()
        dut.icache.logic.invalidateAll #= false
      }
      fetch(dut, cd, 0x1040L)
      racer.join()
      cd.waitSampling(20)

      assert(rsps == 1,
        s"exactly one response must be delivered for one issued fetch, got $rsps -- a cancelled " +
          "response would strand a FetchAlign ring entry (ringCount has no other decrement path " +
          "and no timeout), permanently blocking ic.cmd.valid")
    }
  }

  private def fetch(dut: Dut, cd: ClockDomain, pc: Long): BigInt = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    dut.probe.logic.rspOut.payload.data.toBigInt
  }
}
