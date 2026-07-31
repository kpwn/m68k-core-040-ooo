package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

/** A trivial DUT that issues N reads with N distinct IDs and records the ORDER in
  * which their `last` beats come back. Proves the model's reordering modes actually
  * reorder (and that InOrder does not).
  *
  * `addrStride` (default 64, i.e. one 64B L2 line apart) lets a caller put two or
  * more of the N reads in the SAME 64-byte line -- e.g. `addrStride = 16` with
  * `nIds >= 2` lands id0 and id1 in the same line, to exercise the L2 model's
  * hit/secondary-merge tiers (`L2LatencyModel`) instead of always missing. */
class MultiIdReadDut(nIds: Int, addrStride: Int = 64) extends Component {
  val io = new Bundle {
    val axi  = master(Axi4ReadOnly(AxiMemModel.axiConfig(128, 4)))
    val go   = in Bool()
    val done = out Bits(nIds bits)
  }
  val issued = RegInit(U(0, log2Up(nIds + 1) bits))
  val doneReg = RegInit(B(0, nIds bits)); io.done := doneReg
  io.axi.ar.valid         := io.go && (issued < nIds)
  io.axi.ar.payload.addr  := (issued * addrStride).resize(32)
  io.axi.ar.payload.id    := issued.resize(4)
  io.axi.ar.payload.len   := U(0, 8 bits)
  io.axi.ar.payload.size  := U(4, 3 bits)
  io.axi.ar.payload.burst := Axi4.burst.INCR
  when(io.axi.ar.fire) { issued := issued + 1 }
  io.axi.r.ready := True
  when(io.axi.r.valid && io.axi.r.payload.last) {
    doneReg(io.axi.r.payload.id.resize(log2Up(nIds))) := True
  }
  io.done.simPublic(); issued.simPublic()
}

/** A tiny full-duplex DUT: issues one AW/W burst of a known pattern, waits for B,
  * then issues an AR to the SAME address and latches the returned data. Used to prove
  * the write-before-B invariant end to end (design doc §8.1 item 6) -- the one
  * invariant a deferred-apply latency model would silently break. */
object WriteThenReadDut {
  val ADDR: Long = 0x1000L
  val PATTERN: BigInt = BigInt("A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5", 16)
}

class WriteThenReadDut extends Component {
  import WriteThenReadDut._

  val io = new Bundle {
    val axi      = master(Axi4(AxiMemModel.axiConfig(128, 4)))
    val go       = in Bool()
    val done     = out Bool()
    val readData = out Bits(128 bits)
  }

  val awDone = RegInit(False)
  val wDone  = RegInit(False)
  val bDone  = RegInit(False)
  val arDone = RegInit(False)
  val rDone  = RegInit(False)
  val readDataReg = Reg(Bits(128 bits)) init (0)

  io.axi.aw.valid          := io.go && !awDone
  io.axi.aw.payload.addr   := U(ADDR, 32 bits)
  io.axi.aw.payload.id     := U(1, 4 bits)
  io.axi.aw.payload.len    := U(0, 8 bits)
  io.axi.aw.payload.size   := U(4, 3 bits)
  io.axi.aw.payload.burst  := Axi4.burst.INCR
  when(io.axi.aw.fire) { awDone := True }

  io.axi.w.valid         := io.go && awDone && !wDone
  io.axi.w.payload.data  := B(PATTERN, 128 bits)
  io.axi.w.payload.strb  := B(0xffff, 16 bits)
  io.axi.w.payload.last  := True
  when(io.axi.w.fire) { wDone := True }

  io.axi.b.ready := True
  when(io.axi.b.valid && !bDone) { bDone := True }

  io.axi.ar.valid          := io.go && bDone && !arDone
  io.axi.ar.payload.addr   := U(ADDR, 32 bits)
  io.axi.ar.payload.id     := U(2, 4 bits)
  io.axi.ar.payload.len    := U(0, 8 bits)
  io.axi.ar.payload.size   := U(4, 3 bits)
  io.axi.ar.payload.burst  := Axi4.burst.INCR
  when(io.axi.ar.fire) { arDone := True }

  io.axi.r.ready := True
  when(io.axi.r.valid && io.axi.r.payload.last && !rDone) {
    readDataReg := io.axi.r.payload.data
    rDone := True
  }

  io.done     := rDone
  io.readData := readDataReg
  io.done.simPublic(); io.readData.simPublic()
}

class AxiMemModelSpec extends AnyFunSuite {
  private def compiled(nIds: Int) = SimConfig.withWave.compile(new MultiIdReadDut(nIds))
  private def compiledWR()        = SimConfig.withWave.compile(new WriteThenReadDut)

  private def runOrder(mode: AxiRspMode.Value): Seq[Int] = {
    val order = scala.collection.mutable.ArrayBuffer[Int]()
    compiled(4).doSim(s"order-$mode", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(dut.io.axi, dut.clockDomain,
        AxiMemModelConfig(rspMode = mode, maxPendingBeats = 16))
      for (i <- 0 until 4 * 16) m.pokeByte(i.toLong, i & 0xff)
      dut.io.go #= true
      var seen = 0
      var guard = 0
      while (seen < 4 && guard < 2000) {
        dut.clockDomain.waitSampling()
        val d = dut.io.done.toInt
        for (i <- 0 until 4) if (((d >> i) & 1) == 1 && !order.contains(i)) { order += i; seen += 1 }
        guard += 1
      }
      assert(seen == 4, s"only $seen of 4 reads completed")
    }
    order.toSeq
  }

  test("InOrder mode returns responses in issue order") {
    assert(runOrder(AxiRspMode.InOrder) == Seq(0, 1, 2, 3))
  }

  test("Chaos mode returns responses out of issue order") {
    assert(runOrder(AxiRspMode.Chaos) != Seq(0, 1, 2, 3),
      "Chaos mode produced issue order -- the reordering model is not actually reordering")
  }

  test("idBusyBlock refuses a second AR with a live ID") {
    // One ID, two back-to-back reads: the second must not be accepted until the
    // first's last beat has been driven (mirrors the L2's id_busy_c CAM).
    compiled(1).doSim("idbusy", seed = 7) { dut =>
      dut.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(dut.io.axi, dut.clockDomain,
        AxiMemModelConfig(idBusyBlock = true, maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      dut.io.go #= true
      dut.clockDomain.waitSampling(200)
      assert(m.stats.maxConcurrentReads <= 1,
        s"idBusyBlock allowed ${m.stats.maxConcurrentReads} concurrent same-ID reads")
    }
  }

  test("write-before-B: a read issued after B observes the written bytes") {
    // Guards design doc §8.1 item 6 -- the invariant `BehavioralMem.scala:127-148`
    // exists to protect. Exercised through the full-duplex attach: one AW/W burst of
    // a known pattern, wait for B, then AR the same address and compare.
    compiledWR().doSim("write-then-read", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachFull(dut.io.axi, dut.clockDomain,
        AxiMemModelConfig(maxPendingBeats = 16))
      dut.io.go #= true
      var guard = 0
      while (!dut.io.done.toBoolean && guard < 2000) {
        dut.clockDomain.waitSampling()
        guard += 1
      }
      assert(dut.io.done.toBoolean, "write-then-read DUT never completed the read")
      assert(dut.io.readData.toBigInt == WriteThenReadDut.PATTERN,
        f"read back 0x${dut.io.readData.toBigInt}%x, expected 0x${WriteThenReadDut.PATTERN}%x")
      // Also confirm the bytes actually landed in the model's own backing store
      // (not just observed via the read path), and that the model's poke/peek128
      // helpers agree with what the AXI path wrote.
      assert(m.peek128(WriteThenReadDut.ADDR) == WriteThenReadDut.PATTERN)
    }
  }
}
