package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class StoreQueueSpec extends AnyFunSuite {

  def alloc(dut: StoreQueue, cd: ClockDomain, robId: Int, paddr: Long, data: Long, size: SpinalEnumElement[Size.type],
            vaddr: Long = -1, cacheMode: SpinalEnumElement[m68k040.cache.CacheMode.type] = m68k040.cache.CacheMode.WRITETHROUGH,
            supervisor: Boolean = false, precise: Boolean = false): Unit = {
    dut.io.alloc.valid #= true
    dut.io.alloc.payload.robId #= robId
    dut.io.alloc.payload.paddr #= paddr
    // default: logical == physical (identity), matching most callers' intent unless overridden.
    dut.io.alloc.payload.vaddr #= (if (vaddr == -1) paddr else vaddr)
    dut.io.alloc.payload.data #= data
    dut.io.alloc.payload.size #= size
    // aligned (single-slot) store: covered byte count from the size, no slot B.
    dut.io.alloc.payload.nbytesA #= (size match { case Size.BYTE => 1; case Size.WORD => 2; case _ => 4 })
    dut.io.alloc.payload.useStrbA #= false
    dut.io.alloc.payload.strbA #= 0
    dut.io.alloc.payload.lineDataA #= 0
    dut.io.alloc.payload.validB #= false
    dut.io.alloc.payload.paddrB #= 0
    dut.io.alloc.payload.vaddrB #= 0
    dut.io.alloc.payload.nbytesB #= 0
    dut.io.alloc.payload.strbB #= 0
    dut.io.alloc.payload.lineDataB #= 0
    dut.io.alloc.payload.cacheMode #= cacheMode
    dut.io.alloc.payload.supervisor #= supervisor
    dut.io.alloc.payload.precise #= precise
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
    dut.io.commitB.valid #= false; dut.io.commitB.payload #= 0
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

  test("youngest of two overlapping stores forwards (pre-registered bounds, value identical)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // two aligned LONG stores to the SAME addr; the YOUNGER (robId 6) must win.
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0x11111111L, Size.LONG)
      alloc(dut, cd, robId = 6, paddr = 0x100, data = 0x22222222L, Size.LONG)
      // load younger than both -> youngest matching store (robId 6) forwards.
      setQuery(dut, robId = 9, paddr = 0x100, Size.LONG)
      cd.waitSampling()
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "youngest full-overlap should hit")
      assert(dut.io.fwd.rsp.data.toLong == 0x22222222L,
        s"youngest store wins: ${dut.io.fwd.rsp.data.toLong.toHexString}")
      assert(!dut.io.fwd.rsp.stall.toBoolean, "clean full forward -> no stall")
      // a load BETWEEN the two stores (older than robId 6, younger than robId 4) -> only
      // the older store (robId 4) is in age range -> forwards the OLD value.
      setQuery(dut, robId = 5, paddr = 0x100, Size.LONG)
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "older-only overlap still full-hits")
      assert(dut.io.fwd.rsp.data.toLong == 0x11111111L,
        s"between-load sees the older store: ${dut.io.fwd.rsp.data.toLong.toHexString}")
      cd.waitSampling(2)
    }
  }

  test("forward partial-overlap boundary cases (pre-registered range bounds)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // WORD store at 0x102..0x103 (cache line 0x100..0x10F, 16-byte lines).
      alloc(dut, cd, robId = 4, paddr = 0x102, data = 0xBEEFL, Size.WORD)
      // load BYTE at 0x101 -> just below the store BYTE range (paddrLo=0x102) -> NO byte
      // overlap (so NOT a forward: hit=false), but SAME cache line as the in-flight store
      // -> a refill of that line would cache a stale copy (the store's bytes not yet in
      // memory + write-no-allocate) -> the load MUST stall until the store drains.
      setQuery(dut, robId = 6, paddr = 0x101, Size.BYTE)
      cd.waitSampling()
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "byte just below the store range is NOT a byte forward")
      assert(dut.io.fwd.rsp.stall.toBoolean, "same-cache-line in-flight store -> stall (refill hazard)")
      // load BYTE at 0x103 -> inside the store range -> partial overlap -> stall.
      setQuery(dut, robId = 6, paddr = 0x103, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "sub-range byte is not a full forward")
      assert(dut.io.fwd.rsp.stall.toBoolean, "byte inside the store range -> stall")
      // load BYTE at 0x104 -> just above the store BYTE range (paddrHi=0x104), STILL the
      // same cache line (0x100) -> no byte forward, but the same-line refill hazard -> stall.
      setQuery(dut, robId = 6, paddr = 0x104, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "byte just above the store range is NOT a byte forward")
      assert(dut.io.fwd.rsp.stall.toBoolean, "same-cache-line in-flight store -> stall (refill hazard)")
      // load BYTE at 0x110 -> a DIFFERENT cache line -> no byte overlap AND no same-line
      // hazard -> the load proceeds (no forward, no stall).
      setQuery(dut, robId = 6, paddr = 0x110, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean,
        "a different cache line must not overlap and must not stall")
      cd.waitSampling(2)
    }
  }

  test("io.full asserts at depth; a drain frees a slot so a further alloc succeeds", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // Fill the ring to depth WITHOUT draining: alloc 8 distinct aligned stores, all
      // committed (so the head is drainable) but hold drainAck LOW so nothing pops.
      // io.full must NOT assert before the 8th, and MUST assert after it.
      for (i <- 0 until 8) {
        sleep(1)
        assert(!dut.io.full.toBoolean, s"must not be full with $i entries")
        alloc(dut, cd, robId = 10 + i, paddr = 0x100 + i * 0x10, data = 0x1000L + i, Size.LONG)
      }
      sleep(1)
      assert(dut.io.full.toBoolean, "ring must be full after 8 allocs")

      // Commit the oldest (robId 10) so it becomes drainable. Hold drainAck off until
      // the drain is presented, then ack exactly once -> the head pops -> a slot frees.
      commit(dut, cd, robId = 10)
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x100, "oldest (0x100) drains first")
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(!dut.io.full.toBoolean, "io.full must deassert after one entry drains/pops")

      // A 9th alloc now succeeds (does not overrun) and is forwardable to a younger load.
      alloc(dut, cd, robId = 20, paddr = 0x300, data = 0xABCD1234L, Size.LONG)
      sleep(1)
      assert(dut.io.full.toBoolean, "back to full after the 9th alloc refills the freed slot")
      setQuery(dut, robId = 30, paddr = 0x300, Size.LONG)
      cd.waitSampling()
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "the newly-allocated entry must forward")
      assert(dut.io.fwd.rsp.data.toLong == 0xABCD1234L,
        s"forward data ${dut.io.fwd.rsp.data.toLong.toHexString}")
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

  test("P2.1: alloc stores per-entry vaddr/cacheMode/supervisor/precise (SqAlloc widening)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // slot A: logical addr 0x2000_0100, physical 0x100 (identity NOT assumed -- a
      // deliberately DIFFERENT vaddr/paddr pair, so this test can't pass by accident
      // if the two ever got swapped/aliased).
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG,
        vaddr = 0x20000100L, cacheMode = m68k040.cache.CacheMode.COPYBACK,
        supervisor = true, precise = true)
      sleep(1)
      val head = dut.head.toInt
      assert(dut.vaddrAs(head).toLong == 0x20000100L,
        s"vaddrA mismatch: ${dut.vaddrAs(head).toLong.toHexString}")
      assert(dut.cacheModes(head).toEnum == m68k040.cache.CacheMode.COPYBACK,
        s"cacheMode mismatch: ${dut.cacheModes(head).toEnum}")
      assert(dut.supervisors(head).toBoolean, "supervisor bit not stored")
      assert(dut.precises(head).toBoolean, "precise bit not stored")

      // a second, non-supervisor/fast entry -> fields must be independently tracked
      // per-slot (not a single shared latch).
      alloc(dut, cd, robId = 8, paddr = 0x200, data = 0x11111111L, Size.LONG,
        vaddr = 0x30000200L, cacheMode = m68k040.cache.CacheMode.WRITETHROUGH,
        supervisor = false, precise = false)
      sleep(1)
      val head2 = (head + 1) & 7
      assert(dut.vaddrAs(head2).toLong == 0x30000200L,
        s"second entry vaddrA mismatch: ${dut.vaddrAs(head2).toLong.toHexString}")
      assert(dut.cacheModes(head2).toEnum == m68k040.cache.CacheMode.WRITETHROUGH,
        s"second entry cacheMode mismatch: ${dut.cacheModes(head2).toEnum}")
      assert(!dut.supervisors(head2).toBoolean, "second entry supervisor should be false")
      assert(!dut.precises(head2).toBoolean, "second entry precise should be false")
      // the FIRST entry's fields must be unaffected by the second alloc.
      assert(dut.supervisors(head).toBoolean, "first entry supervisor clobbered by second alloc")
      assert(dut.precises(head).toBoolean, "first entry precise clobbered by second alloc")
      cd.waitSampling(2)
    }
  }
}
