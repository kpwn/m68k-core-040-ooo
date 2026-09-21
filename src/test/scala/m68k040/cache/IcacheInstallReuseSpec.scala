package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Board-driven hypothesis: poisoned installer must not consume a recycled MSHR. */
class IcacheInstallReuseSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val param = new ParamPlugin(M68kParams())
    val xlate = new IdentityTranslationPlugin
    val icache = new IcachePlugin(IcachePredecodeConfig.fromEnvironment)
    val probe = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  test("poisoned installer cannot publish a recycled slot tag", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val axi = dut.icache.logic.axi
      val cmd = dut.probe.logic.cmdIn
      val rsp = dut.probe.logic.rspOut
      val requests = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.id #= 0
      axi.r.payload.data #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cmd.valid #= false
      cmd.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          requests += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
      }
      def beat(address: Long, phase: Int): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (acc, i) =>
          val a = address + phase * 32 + i * 4
          acc | (BigInt(((a * 0x9e3779b1L) ^ (a >>> 7) ^ 0xc0ffee42L) & 0xffffffffL) << (i * 32))
        }
      def sendBeat(id: Int, address: Long, phase: Int): Unit = {
        axi.r.valid #= true
        axi.r.payload.id #= id
        axi.r.payload.data #= beat(address, phase)
        axi.r.payload.last #= (phase == 1)
        cd.waitSamplingWhere(axi.r.ready.toBoolean)
        axi.r.valid #= false
      }
      def waitFor(condition: => Boolean, label: String): Unit = {
        var cycles = 0
        while (!condition && cycles < 2000) { cd.waitSampling(); cycles += 1 }
        assert(condition, s"timeout: $label; requests=$requests")
      }
      def warm(address: Long): Unit = {
        val start = requests.size
        fork {
          waitFor(requests.drop(start).contains((0, address)), "warm AR")
          sendBeat(0, address, 0)
          cd.waitSampling()
          sendBeat(0, address, 1)
        }
        cmd.payload.pc #= address
        cmd.valid #= true
        cd.waitSamplingWhere(cmd.ready.toBoolean)
        cmd.valid #= false
        cd.waitSamplingWhere(rsp.valid.toBoolean)
        assert(!rsp.payload.fault.toBoolean)
        assert(rsp.payload.data.toBigInt == (beat(address, 0) & ((BigInt(1) << 64) - 1)))
        cd.waitSampling(4)
      }
      cd.waitSampling(5)
      dut.icache.logic.prefetchEnable #= false
      warm(0x8180L)
      warm(0x2000L)
      dut.icache.logic.prefetchEnable #= true
      // A hit starts the 0x2040 prefetch; leave all speculative returns held.
      cmd.payload.pc #= 0x2000L
      cmd.valid #= true
      cd.waitSamplingWhere(cmd.ready.toBoolean)
      cmd.valid #= false
      waitFor(requests.contains((1, 0x2040L)), "prefetch2040")
      sendBeat(1, 0x2040L, 0)
      cd.waitSampling()
      // Accept the unrelated resident hit on the final refill-beat edge.
      cmd.payload.pc #= 0x8180L
      cmd.valid #= true
      sleep(1)
      assert(cmd.ready.toBoolean, "setup requires resident hit accepted with RLAST")
      sendBeat(1, 0x2040L, 1)
      cmd.valid #= false
      sleep(1)
      assert(dut.icache.logic.mshrComplete(1).toBoolean)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling()
      dut.icache.logic.invalidateAll #= false
      for (cycle <- 0 until 10) {
        sleep(1)
        println(s"reuse cycle=$cycle pred=${dut.icache.logic.predActive.toBoolean} " +
          s"beat=${dut.icache.logic.commitBeat.toInt} " +
          s"slot1valid=${dut.icache.logic.mshrValid(1).toBoolean} " +
          f"slot1pa=${dut.icache.logic.mshrPa(1).toLong}%x " +
          s"set1valid=${(0 until 4).map(w => dut.icache.logic.valids(w)(1).toBoolean)}")
        // Both resident lines were invalidated. The old2040 fill is poisoned;
        // no new R beat has been supplied. Publishing ANY set1 line is illegal.
        cd.waitSampling()
      }
      val published = (0 until 4).filter(w => dut.icache.logic.valids(w)(1).toBoolean)
      published.foreach { w =>
        println(s"published way=$w " + IcacheArrayProbe.snapshotWay(dut.icache, w, 1).describe)
      }
      // New tag8 at old set1 names8040, not the newly allocated81c0.
      // A correct implementation must miss and refill. A corrupt hit bypasses
      // this responder and is rejected by the independent address-based oracle.
      val beforeRetry = requests.size
      fork {
        waitFor(requests.drop(beforeRetry).contains((0, 0x8040L)), "retry demand AR")
        sendBeat(0, 0x8040L, 0)
        cd.waitSampling()
        sendBeat(0, 0x8040L, 1)
      }
      cmd.payload.pc #= 0x8060L
      cmd.valid #= true
      waitFor(cmd.ready.toBoolean, "corrupt-line command ready")
      cd.waitSampling()
      cmd.valid #= false
      waitFor(rsp.valid.toBoolean, "corrupt-line cache response")
      val observed = rsp.payload.data.toBigInt
      val expected = beat(0x8040L, 1) & ((BigInt(1) << 64) - 1)
      val stale = beat(0x2040L, 1) & ((BigInt(1) << 64) - 1)
      println(s"fetch8060 expected=${expected.toString(16)} observed=${observed.toString(16)} old2060=${stale.toString(16)}")
      assert(observed == expected, "recycled installer returned wrong instruction bytes at8060")
    }
  }
}
