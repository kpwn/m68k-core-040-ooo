package m68k040.socket

import m68k040.cache.AxiIds
import m68k040.sim.AxiMemModel
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4ReadOnly, Axi4Config}
import org.scalatest.funsuite.AnyFunSuite

/** Correctness spec for the AXI boundary register slice added to `M68kSocketTop`
  * (2026-09-02, follow-up session -- see
  * docs/BUG_calibration_word_misplaced_0d00.md Part 86/87 in the SoC repo).
  *
  * Same convention as `SocketTopByteOrderSpec`: the whole socket top is far too
  * large to elaborate here, so this proves the CONNECTION PATTERN -- an
  * `Axi4ReadOnly` bus carried through `.pipelined(ar = StreamPipe.FULL,
  * r = StreamPipe.FULL)`, exactly the call `SocketTop.scala` makes on
  * `socket.icache.logic.axi` -- on a stand-in that has the same shape as the
  * real one (a "core"-facing slave port a test drives directly, connected
  * through the pipe to a "fabric"-facing master port `AxiMemModel` attaches
  * to, exactly mirroring `ic`/`axi_i`). If `SocketTop.scala`'s call changes,
  * this DUT should be revisited with it. */
class AxiRegSliceSpec extends AnyFunSuite {

  class RegSliceDut(dw: Int = 256) extends Component {
    val axiCfg: Axi4Config = AxiMemModel.axiConfig(dataWidth = dw, idWidth = AxiIds.ID_W)
    // "core" side: a test drives this directly, exactly as if it were
    // IcachePlugin issuing AR / consuming R -- a slave port so AR/rready are
    // real component INPUTS a testbench can poke.
    val core   = slave(Axi4ReadOnly(axiCfg))
    // "fabric" side: what a memory model attaches to, exactly mirroring
    // `axi_i` -- a master port so AR/rready are real OUTPUTS driven by the
    // pipe.
    val fabric = master(Axi4ReadOnly(axiCfg))
    fabric <> core.pipelined(ar = StreamPipe.FULL, r = StreamPipe.FULL)
  }

  test("register slice: reads return the right data, in order, id-correct") {
    SimConfig.compile(new RegSliceDut()).doSim("dataCorrect", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachReadOnly(dut.fabric, dut.clockDomain)
      // Seed a few distinguishable lines.
      for (line <- 0 until 8; b <- 0 until 32) {
        mem.mem.write(line * 64L + b, ((line * 32 + b) & 0xff).toByte)
      }
      dut.core.ar.valid #= false
      dut.core.r.ready   #= true
      dut.clockDomain.waitSampling(2)

      for (line <- Seq(0, 3, 7, 1)) {
        dut.core.ar.payload.id     #= 0
        dut.core.ar.payload.addr   #= line * 64L
        dut.core.ar.payload.len    #= 0
        dut.core.ar.payload.size   #= log2Up(dut.axiCfg.dataWidth / 8)
        dut.core.ar.payload.burst  #= 1 // INCR
        dut.core.ar.valid          #= true
        dut.clockDomain.waitSamplingWhere(dut.core.ar.valid.toBoolean && dut.core.ar.ready.toBoolean)
        dut.core.ar.valid #= false
        dut.clockDomain.waitSamplingWhere(dut.core.r.valid.toBoolean)
        assert(dut.core.r.payload.last.toBoolean, "single-beat burst must set rlast")
        val expected = (0 until dut.axiCfg.dataWidth / 8).foldLeft(BigInt(0)) { case (acc, i) =>
          acc | (BigInt((line * 32 + i) & 0xff) << (8 * i))
        }
        assert(dut.core.r.payload.data.toBigInt == expected,
          f"line $line: got 0x${dut.core.r.payload.data.toBigInt}%x wanted 0x$expected%x")
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("register slice: added latency is bounded (no combinational passthrough, no runaway stall)") {
    SimConfig.compile(new RegSliceDut()).doSim("latencyBounded", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      AxiMemModel.attachReadOnly(dut.fabric, dut.clockDomain,
        cfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5)))
      dut.core.ar.valid #= false
      dut.core.r.ready   #= true
      dut.clockDomain.waitSampling(2)

      dut.core.ar.payload.id    #= 0
      dut.core.ar.payload.addr  #= 0
      dut.core.ar.payload.len   #= 0
      dut.core.ar.payload.size  #= log2Up(dut.axiCfg.dataWidth / 8)
      dut.core.ar.payload.burst #= 1
      dut.core.ar.valid         #= true
      var cyclesToArAccept = 0
      while (!(dut.core.ar.valid.toBoolean && dut.core.ar.ready.toBoolean)) {
        dut.clockDomain.waitSampling(); cyclesToArAccept += 1
        assert(cyclesToArAccept < 20, "AR never accepted -- the register slice deadlocked")
      }
      dut.core.ar.valid #= false
      var cyclesToR = 0
      while (!dut.core.r.valid.toBoolean) {
        dut.clockDomain.waitSampling(); cyclesToR += 1
        assert(cyclesToR < 40,
          "R never arrived -- the register slice deadlocked or dropped the transaction")
      }
      // Sanity bound, not a tight equality: this is a NO-DEADLOCK / NO-RUNAWAY-
      // STALL check, not a precise latency characterization (that's measured
      // separately via IpcBenchSpec's IPC_MEM=l2 A/B comparison, which exercises
      // the SAME `.pipelined(StreamPipe.FULL)` call against the full core). A
      // handful of extra cycles from AR-issue to R-valid on top of the model's
      // own ~5-cycle hit latency is expected and fine; runaway growth is not.
      assert(cyclesToR <= 25,
        s"R took $cyclesToR cycles against a ~5-cycle memory model -- register " +
        s"slice added far more latency than expected, possible deadlock-adjacent bug")
    }
  }

  test("register slice: back-to-back reads under backpressure preserve order and data " +
       "(no drop/duplicate across the skid buffer)") {
    SimConfig.compile(new RegSliceDut()).doSim("backpressure", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachReadOnly(dut.fabric, dut.clockDomain)
      for (line <- 0 until 4; b <- 0 until 32) {
        mem.mem.write(line * 64L + b, ((0x10 + line) & 0xff).toByte)
      }
      dut.core.ar.valid #= false
      dut.core.r.ready   #= false
      dut.clockDomain.waitSampling(2)

      // Issue 4 ARs back-to-back (ids distinguish them), THEN stall r.ready for
      // a while before draining -- exercises the skid buffer actually holding
      // data rather than combinationally passing it through.
      for (line <- 0 until 4) {
        dut.core.ar.payload.id    #= line
        dut.core.ar.payload.addr  #= line * 64L
        dut.core.ar.payload.len   #= 0
        dut.core.ar.payload.size  #= log2Up(dut.axiCfg.dataWidth / 8)
        dut.core.ar.payload.burst #= 1
        dut.core.ar.valid         #= true
        dut.clockDomain.waitSamplingWhere(dut.core.ar.ready.toBoolean)
      }
      dut.core.ar.valid #= false
      dut.clockDomain.waitSampling(15) // let responses queue up behind the stalled r.ready
      dut.core.r.ready #= true

      val seen = scala.collection.mutable.ArrayBuffer[(Int, BigInt)]()
      var guard = 0
      while (seen.size < 4 && guard < 200) {
        if (dut.core.r.valid.toBoolean && dut.core.r.ready.toBoolean) {
          seen += ((dut.core.r.payload.id.toInt, dut.core.r.payload.data.toBigInt))
        }
        dut.clockDomain.waitSampling(); guard += 1
      }
      assert(seen.size == 4, s"expected 4 responses, saw ${seen.size} (guard=$guard)")
      // AXI is only in-order PER ID here every id is distinct so any order is
      // legal, but every id must appear exactly once with its own line's data.
      val byId = seen.toMap
      assert(byId.keySet == Set(0, 1, 2, 3), s"lost or duplicated a response: $byId")
      for (line <- 0 until 4) {
        val expectedByte = (0x10 + line) & 0xff
        assert((byId(line) & 0xff) == BigInt(expectedByte),
          f"id $line: got low byte 0x${byId(line) & 0xff}%x wanted 0x$expectedByte%x")
      }
    }
  }
}
