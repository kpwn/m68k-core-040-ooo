package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class StoreQueueSpec extends AnyFunSuite {

  def alloc(dut: StoreQueue, cd: ClockDomain, robId: Int, paddr: Long, data: Long, size: SpinalEnumElement[Size.type]): Unit = {
    dut.io.alloc.valid #= true
    dut.io.alloc.payload.robId #= robId
    dut.io.alloc.payload.paddr #= paddr
    dut.io.alloc.payload.data #= data
    dut.io.alloc.payload.size #= size
    cd.waitSampling()
    dut.io.alloc.valid #= false
  }

  def commit(dut: StoreQueue, cd: ClockDomain, robId: Int): Unit = {
    dut.io.commit.valid #= true
    dut.io.commit.payload #= robId
    cd.waitSampling()
    dut.io.commit.valid #= false
  }

  def setQuery(dut: StoreQueue, robId: Int, paddr: Long, size: SpinalEnumElement[Size.type]): Unit = {
    dut.io.fwd.query.robId #= robId
    dut.io.fwd.query.paddr #= paddr
    dut.io.fwd.query.size #= size
  }

  def initDut(dut: StoreQueue): ClockDomain = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    dut.io.alloc.valid #= false
    dut.io.commit.valid #= false
    dut.io.flush #= false
    dut.io.drainAck #= false
    setQuery(dut, 0, 0, Size.LONG)
    cd.waitSampling(3)
    cd
  }

  /** Model the D-cache's write-through ack: a drained store is presented for one
    * cycle (io.drain.valid) then HELD resident until ack. This fork acks the cycle
    * after each drain-issue (a 1-cycle write-through), mirroring DcachePlugin.storeAck. */
  def forkDrainAck(dut: StoreQueue, cd: ClockDomain): Unit = fork {
    while (true) {
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
    }
  }

  test("alloc then a younger load forwards full-overlap data", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG)
      // younger load (robId 6) same addr/size -> full forward
      setQuery(dut, robId = 6, paddr = 0x100, Size.LONG)
      cd.waitSampling()
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "should hit")
      assert(dut.io.fwd.rsp.data.toLong == 0xCAFEBABEL, s"data ${dut.io.fwd.rsp.data.toLong.toHexString}")
      assert(!dut.io.fwd.rsp.stall.toBoolean, "no stall")
      // non-overlapping query -> no hit
      setQuery(dut, robId = 6, paddr = 0x200, Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "no overlap -> no hit")
      assert(!dut.io.fwd.rsp.stall.toBoolean, "no overlap -> no stall")
      // an OLDER load (robId 2, before the store) must NOT forward
      setQuery(dut, robId = 2, paddr = 0x100, Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "older load must not forward from a younger store")
      cd.waitSampling(2)
    }
  }

  test("committed oldest entry drains; flush squashes uncommitted younger entry", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      forkDrainAck(dut, cd)
      // A (older, robId 4) committed; B (younger, robId 8) uncommitted
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0x11111111L, Size.LONG)
      alloc(dut, cd, robId = 8, paddr = 0x200, data = 0x22222222L, Size.LONG)
      // before commit, head (A) must NOT drain
      sleep(1)
      assert(!dut.io.drain.valid.toBoolean, "uncommitted head must not drain")

      // observe every drain over the rest of the test (auto-drain, one/cycle)
      val drained = scala.collection.mutable.ListBuffer[(Long, Long)]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean)
          drained += ((dut.io.drain.payload.paddr.toLong, dut.io.drain.payload.data.toLong)) } }

      // flush BEFORE committing A: A committed? no -> but A is older & we commit it
      // first to keep it, then flush squashes only the uncommitted B.
      commit(dut, cd, robId = 4)        // A committed
      // flush now: A committed (kept), B uncommitted (squashed, must never drain)
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      cd.waitSampling(6)                // let A drain and pop

      assert(drained.exists(_._1 == 0x100), "committed A (0x100) must drain")
      assert(!drained.exists(_._1 == 0x200), "squashed B (0x200) must NEVER drain")
      // after A drains the queue is empty
      sleep(1)
      assert(!dut.io.drain.valid.toBoolean, "empty after A drains")
      cd.waitSampling(2)
    }
  }

  test("drained-but-unacked entry still forwards (drain-window hazard)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // store committed -> it will be presented on io.drain. Hold ack OFF so the
      // entry stays in the drain window (memory write not yet acknowledged).
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG)
      commit(dut, cd, robId = 4)
      // wait for the drain to be presented (io.drain.valid pulses), keep ack low
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      // NEXT cycles: entry is "drained" (presented) but UNACKED -> must still be
      // resident and forward to a younger load that would otherwise miss L1D.
      cd.waitSampling()
      setQuery(dut, robId = 6, paddr = 0x100, Size.LONG)
      sleep(1)
      assert(dut.io.drain.valid.toBoolean == false, "must not re-present an in-flight drain")
      assert(dut.io.fwd.rsp.hit.toBoolean, "drained-but-unacked store must still forward")
      assert(dut.io.fwd.rsp.data.toLong == 0xCAFEBABEL, s"fwd data ${dut.io.fwd.rsp.data.toLong.toHexString}")
      // now ack -> entry pops, forwarding stops
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling()
      setQuery(dut, robId = 6, paddr = 0x100, Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "after ack+pop the entry no longer forwards")
      cd.waitSampling(2)
    }
  }

  test("partial overlap with an older store stalls the load", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // store a LONG at 0x100..0x103
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0xDEADBEEFL, Size.LONG)
      // younger load of a WORD at 0x102 -> partial overlap (not exact addr/size) -> stall
      setQuery(dut, robId = 6, paddr = 0x102, Size.WORD)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "partial -> no full hit")
      assert(dut.io.fwd.rsp.stall.toBoolean, "partial overlap -> stall")
      cd.waitSampling(2)
    }
  }
}
