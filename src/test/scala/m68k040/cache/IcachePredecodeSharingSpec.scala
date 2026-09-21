package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Compare the delivered metadata, not a second software implementation of it.
  * The sixteen-wide implementation is the reference for sharing/word pairing;
  * independent byte checks and explicit phase coverage prevent vacuous matches.
  */
class IcachePredecodeSharingSpec extends AnyFunSuite {
  class Dut(width: Int) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val param = new ParamPlugin(M68kParams())
    val xlate = new IdentityTranslationPlugin
    val icache = new IcachePlugin(width)
    val probe = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  case class Sample(pc: Long, data: BigInt, pred: BigInt)

  // Every line has distinct contents. Place extension-sensitive instructions at
  // all three boundaries: eight words, AXI beat, and real cache-line end.
  private def words(address: Long): Vector[Int] = {
    val rng = new scala.util.Random(address ^ 0x680408L)
    val a = Array.fill(32)(rng.nextInt(65536))
    val starts = Seq(5, 6, 7, 13, 14, 15, 21, 22, 23, 29, 30, 31)
    val start = starts(((address >>> 6) % starts.size).toInt)
    val pattern = if ((address & 64) == 0) Seq(0x2030, 0x0133, 0x1234, 0x5678)
      else Seq(0x48f0, 0xaaaa, 0x0133, 0x2468)
    pattern.zipWithIndex.filter(p => start + p._2 < 32)
      .foreach { case (w, i) => a(start + i) = w }
    a.toVector
  }

  private def beat(address: Long, phase: Int): BigInt =
    words(address).slice(phase * 16, phase * 16 + 16).zipWithIndex.foldLeft(BigInt(0)) {
      case (v, (w, i)) => v | (BigInt(((w & 255) << 8) | (w >>> 8)) << (16 * i))
    }

  class Driver(dut: Dut) {
    val cd = dut.clockDomain
    val axi = dut.icache.logic.axi
    val cmd = dut.probe.logic.cmdIn
    val rsp = dut.probe.logic.rspOut
    val requests = ArrayBuffer.empty[(Int, Long)]
    val phases = ArrayBuffer.empty[(Int, Boolean)]
    val samples = ArrayBuffer.empty[Sample]
    var busyReturns = 0
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
      if (dut.icache.logic.predActive.toBoolean) {
        phases += ((dut.icache.logic.installIdx.toInt, dut.icache.logic.predBeatDone.toBoolean))
        if (axi.r.valid.toBoolean && axi.r.ready.toBoolean) busyReturns += 1
      }
    }
    def await(condition: => Boolean, label: String): Unit = {
      var n = 0
      // Observe the post-edge state, so an installer trigger leaves its FIRST
      // active cycle available to the response driver rather than skipping it.
      sleep(1)
      while (!condition && n < 3000) { cd.waitSampling(); sleep(1); n += 1 }
      assert(condition, s"timeout: $label; requests=$requests phases=$phases")
    }
    def send(id: Int, address: Long, phase: Int): Unit = {
      axi.r.valid #= true
      axi.r.payload.id #= id
      axi.r.payload.data #= beat(address, phase)
      axi.r.payload.last #= (phase == 1)
      cd.waitSamplingWhere(axi.r.ready.toBoolean)
      axi.r.valid #= false
    }
    def command(pc: Long): Unit = {
      cmd.payload.pc #= pc
      cmd.valid #= true
      await(cmd.ready.toBoolean, "command ready")
      cd.waitSampling()
      cmd.valid #= false
    }
    def response(pc: Long): Unit = {
      await(rsp.valid.toBoolean, "response")
      assert(!rsp.payload.fault.toBoolean)
      assert(rsp.payload.pc.toLong == pc)
      val expected = (beat(pc & ~63L, ((pc >>> 5) & 1).toInt) >>
        ((pc & 31).toInt * 8)) & ((BigInt(1) << 64) - 1)
      assert(rsp.payload.data.toBigInt == expected, f"wrong bytes at $pc%x")
      samples += Sample(pc, rsp.payload.data.toBigInt, dut.probe.logic.rspPredBits.toBigInt)
      cd.waitSampling(3)
    }
    def windows(address: Long): Unit = for (i <- 0 until 8) {
      command(address + i * 8)
      response(address + i * 8)
    }
  }

  test("eight classifiers preserve boundary metadata and handle real installer contention", VerilatorTest) {
    val results = Seq(16, 8).map { width =>
      val compiled = SimConfig.withVerilator.compile(new Dut(width))
      val all = ArrayBuffer.empty[Sample]
      for (gap <- Seq(0, 3)) compiled.doSim(s"sharing${width}_gap$gap", 17) { dut =>
        val d = new Driver(dut)
        val cd = d.cd
        cd.forkStimulus(10)
        cd.waitSampling(5)
        dut.icache.logic.prefetchEnable #= false
        // Reuse demand MSHR 0 repeatedly with distinct line contents.
        for (line <- 0 until 36) {
          val address = 0x1000L + line * 64
          val phaseStart = d.phases.size
          val requestStart = d.requests.size
          fork {
            d.await(d.requests.drop(requestStart).contains((0, address)), "demand AR")
            d.send(0, address, 0)
            if (gap > 0) cd.waitSampling(gap)
            d.send(0, address, 1)
          }
          d.command(address)
          d.response(address)
          assert(d.phases.drop(phaseStart).toVector == Vector((0, true), (0, true)),
            s"uncontended install must take two cycles: width=$width gap=$gap")
          d.windows(address)
        }
        all ++= d.samples
      }
      compiled.doSim(s"sharing${width}_contention", 17) { dut =>
        val d = new Driver(dut)
        val cd = d.cd
        cd.forkStimulus(10)
        cd.waitSampling(5)
        d.command(0x2000L)
        d.await(d.requests.map(_._1).toSet == Set(0, 1, 2, 3, 4), "five owned ARs")
        dut.icache.logic.prefetchEnable #= false
        val addresses = d.requests.toMap
        d.send(0, addresses(0), 0)
        d.send(0, addresses(0), 1)
        d.response(0x2000L)
        d.send(1, addresses(1), 0)
        d.send(1, addresses(1), 1)
        d.await(dut.icache.logic.predActive.toBoolean, "first speculative install")
        assert(dut.icache.logic.installIdx.toInt == 1)
        // Both second-owner beats arrive while owner 1 occupies the classifier.
        d.send(2, addresses(2), 0)
        d.send(2, addresses(2), 1)
        sleep(1)
        assert(d.busyReturns == 2, "did not force two real classifier collisions")
        d.await(!dut.icache.logic.mshrValid(2).toBoolean, "second speculative install done")
        cd.waitSampling(2)
        val second = d.phases.filter(_._1 == 2).map(_._2).toVector
        assert(second == (if (width == 8) Vector(false, true, false, true) else Vector(true, true)),
          s"unexpected fallback schedule: $second")
        d.windows(addresses(1))
        d.windows(addresses(2))
        all ++= d.samples
        println(s"PREDECODE_SHARING width=$width busyReturns=${d.busyReturns} fallbackCycles=${second.count(!_)}")
      }
      all.toVector
    }
    assert(results(0).size == 665)
    assert(results(1).size == results(0).size)
    results(0).zip(results(1)).zipWithIndex.foreach { case ((before, after), index) =>
      assert(after == before, s"sample $index: sixteen=$before eight=$after")
    }
  }

  test("invalidation wins in every eight-way fallback phase", VerilatorTest) {
    val compiled = SimConfig.withVerilator.compile(new Dut(8))
    for (phase <- 0 until 4) compiled.doSim(s"fallback-invalidate-$phase", 17) { dut =>
      val d = new Driver(dut)
      val cd = d.cd
      cd.forkStimulus(10)
      cd.waitSampling(5)
      d.command(0x2000L)
      d.await(d.requests.map(_._1).toSet == Set(0, 1, 2, 3, 4), "five owned ARs")
      dut.icache.logic.prefetchEnable #= false
      val addresses = d.requests.toMap
      d.send(0, addresses(0), 0)
      d.send(0, addresses(0), 1)
      d.response(0x2000L)
      d.send(1, addresses(1), 0)
      d.send(1, addresses(1), 1)
      d.await(dut.icache.logic.predActive.toBoolean, "first installer")
      d.send(2, addresses(2), 0)
      d.send(2, addresses(2), 1)
      d.await(dut.icache.logic.predActive.toBoolean && dut.icache.logic.installIdx.toInt == 2,
        "fallback installer")
      if (phase != 0) { cd.waitSampling(phase); sleep(1) }
      assert(dut.icache.logic.predActive.toBoolean && dut.icache.logic.installIdx.toInt == 2)
      assert(dut.icache.logic.commitBeat.toInt == phase / 2)
      assert(dut.icache.logic.predBeatDone.toBoolean == (phase % 2 == 1))
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling()
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(8)
      val set = ((addresses(2) >>> 6) & 63).toInt
      assert((0 until 4).forall(w => !dut.icache.logic.valids(w)(set).toBoolean),
        s"invalidated line was published at fallback phase $phase")
      val start = d.requests.size
      fork {
        d.await(d.requests.drop(start).contains((0, addresses(2))), "invalidated line must refill")
        d.send(0, addresses(2), 0)
        d.send(0, addresses(2), 1)
      }
      d.command(addresses(2))
      d.response(addresses(2))
      d.windows(addresses(2))
    }
  }
}
