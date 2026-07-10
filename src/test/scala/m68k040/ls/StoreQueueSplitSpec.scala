package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** Directed test of the store-queue two-slot atomic drain + dual-slot forward (Task 4).
  *
  * A SPLIT (cross-line/page) store occupies ONE SQ entry with an optional slot B
  * {paddrB, strbB, lineDataB, nbytesB, validB}. Forwarding checks a younger load
  * against BOTH slots. Commit-drain emits slot A then slot B (two DStoreCmds),
  * popping only after BOTH are drain-ACKed. Flush drops both. */
class StoreQueueSplitSpec extends AnyFunSuite {

  /** Alloc a SPLIT store: slot A at paddrA (nbytesA, explicit strobe/line data),
    * slot B at paddrB (nbytesB). */
  def allocSplit(dut: StoreQueue, cd: ClockDomain, robId: Int,
                 paddrA: Long, nbytesA: Int, strbA: Int, lineA: BigInt,
                 paddrB: Long, nbytesB: Int, strbB: Int, lineB: BigInt): Unit = {
    val a = dut.io.alloc
    a.valid #= true
    a.payload.robId #= robId
    a.payload.paddr #= paddrA
    a.payload.data #= 0
    a.payload.size #= Size.LONG
    a.payload.nbytesA #= nbytesA
    a.payload.useStrbA #= true
    a.payload.strbA #= strbA
    a.payload.lineDataA #= lineA
    a.payload.validB #= true
    a.payload.paddrB #= paddrB
    a.payload.nbytesB #= nbytesB
    a.payload.strbB #= strbB
    a.payload.lineDataB #= lineB
    cd.waitSampling()
    a.valid #= false
  }

  def allocAligned(dut: StoreQueue, cd: ClockDomain, robId: Int, paddr: Long, data: Long, size: SpinalEnumElement[Size.type]): Unit = {
    val a = dut.io.alloc
    a.valid #= true
    a.payload.robId #= robId
    a.payload.paddr #= paddr
    a.payload.data #= data
    a.payload.size #= size
    a.payload.nbytesA #= (size match { case Size.BYTE => 1; case Size.WORD => 2; case _ => 4 })
    a.payload.useStrbA #= false
    a.payload.strbA #= 0
    a.payload.lineDataA #= 0
    a.payload.validB #= false
    a.payload.paddrB #= 0
    a.payload.nbytesB #= 0
    a.payload.strbB #= 0
    a.payload.lineDataB #= 0
    cd.waitSampling()
    a.valid #= false
  }

  def commit(dut: StoreQueue, cd: ClockDomain, robId: Int): Unit = {
    dut.io.commit.valid #= true; dut.io.commit.payload #= robId
    cd.waitSampling(); dut.io.commit.valid #= false
  }

  def setQuery(dut: StoreQueue, robId: Int, paddr: Long, size: SpinalEnumElement[Size.type]): Unit = {
    dut.io.fwd.query.robId #= robId; dut.io.fwd.query.paddr #= paddr; dut.io.fwd.query.size #= size
  }

  def initDut(dut: StoreQueue): ClockDomain = {
    val cd = dut.clockDomain; cd.forkStimulus(period = 10)
    dut.io.alloc.valid #= false; dut.io.commit.valid #= false
    dut.io.commitB.valid #= false; dut.io.commitB.payload #= 0
    dut.io.flush #= false; dut.io.drainAck #= false
    dut.io.alloc.payload.validB #= false; dut.io.alloc.payload.useStrbA #= false
    setQuery(dut, 0, 0, Size.LONG)
    cd.waitSampling(3)
    cd
  }

  /** Auto-ack each presented drain the next cycle (1-cycle write-through). */
  def forkDrainAck(dut: StoreQueue, cd: ClockDomain): Unit = fork {
    while (true) {
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
    }
  }

  test("split store: younger load overlapping slot B does not spuriously hit (stalls)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // split LONG at 0x10E..0x111: slot A = 0x10E (2 bytes: byte 14,15), slot B = 0x110 (2 bytes: byte 0,1)
      allocSplit(dut, cd, robId = 4,
        paddrA = 0x10E, nbytesA = 2, strbA = 0xC000, lineA = BigInt("11220000000000000000000000000000", 16),
        paddrB = 0x110, nbytesB = 2, strbB = 0x0003, lineB = BigInt("00000000000000000000000000003344", 16))
      // younger load (robId 6) of a WORD at 0x110 overlaps slot B -> must NOT full-hit; must stall.
      setQuery(dut, robId = 6, paddr = 0x110, Size.WORD)
      cd.waitSampling(); sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "split-store slot-B overlap must not full-forward")
      assert(dut.io.fwd.rsp.stall.toBoolean, "overlap with slot B -> stall")
      // a non-overlapping load -> no hit, no stall
      setQuery(dut, robId = 6, paddr = 0x200, Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean, "no overlap -> idle")
      cd.waitSampling(2)
    }
  }

  test("split store commit-drain writes BOTH halves; pops only after both ACK", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[(Long, Long)]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean)
          drained += ((dut.io.drain.payload.paddr.toLong, dut.io.drain.payload.strb.toLong)) } }
      forkDrainAck(dut, cd)
      allocSplit(dut, cd, robId = 4,
        paddrA = 0x10E, nbytesA = 2, strbA = 0xC000, lineA = BigInt("11220000000000000000000000000000", 16),
        paddrB = 0x110, nbytesB = 2, strbB = 0x0003, lineB = BigInt("00000000000000000000000000003344", 16))
      // uncommitted -> no drain
      sleep(1); assert(!dut.io.drain.valid.toBoolean, "uncommitted split store must not drain")
      commit(dut, cd, robId = 4)
      cd.waitSampling(12)
      assert(drained.exists(_._1 == 0x10E), "slot A (0x10E) drains")
      assert(drained.exists(_._1 == 0x110), "slot B (0x110) drains")
      // after both halves drain the queue is empty
      sleep(1); assert(!dut.io.drain.valid.toBoolean, "empty after both halves drain")
      cd.waitSampling(2)
    }
  }

  test("flush squashes a split store (neither half drains)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[Long]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean) drained += dut.io.drain.payload.paddr.toLong } }
      forkDrainAck(dut, cd)
      allocSplit(dut, cd, robId = 9,
        paddrA = 0x10E, nbytesA = 2, strbA = 0xC000, lineA = BigInt("11220000000000000000000000000000", 16),
        paddrB = 0x110, nbytesB = 2, strbB = 0x0003, lineB = BigInt("00000000000000000000000000003344", 16))
      // flush before commit -> squashed; neither half ever drains
      dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false
      cd.waitSampling(12)
      assert(!drained.contains(0x10EL), "squashed split store slot A must not drain")
      assert(!drained.contains(0x110L), "squashed split store slot B must not drain")
      cd.waitSampling(2)
    }
  }

  test("aligned store still drains as a single slot (fast path unchanged)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[(Long, Long)]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean)
          drained += ((dut.io.drain.payload.paddr.toLong, dut.io.drain.payload.data.toLong)) } }
      forkDrainAck(dut, cd)
      allocAligned(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG)
      // forward full-overlap still works
      setQuery(dut, robId = 6, paddr = 0x100, Size.LONG)
      cd.waitSampling(); sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "aligned full-overlap still forwards")
      assert(dut.io.fwd.rsp.data.toLong == 0xCAFEBABEL, "forward data")
      commit(dut, cd, robId = 4)
      cd.waitSampling(8)
      assert(drained.count(_._1 == 0x100) == 1, "aligned store drains exactly once")
      cd.waitSampling(2)
    }
  }
}
