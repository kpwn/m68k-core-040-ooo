package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class StoreQueueSpec extends AnyFunSuite {

  for(unfilled <- Seq(false, true)) test(s"youngest SQ match survives committed ROB-index reuse unfilled=$unfilled", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, reserveLateStore = unfilled)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      alloc(dut, cd, 2, 0x100, 0x11111111L, Size.LONG,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      commit(dut, cd, 2)
      // The committed store can outlive an entire ROB generation while its drain
      // is backpressured. A later live store at index 1 is younger than it even
      // though query-index distance ranks the old index 2 as "closer" to index 3.
      dut.io.robHeadIn #= 1; dut.io.robHeadValidIn #= true
      if(unfilled) dut.io.reserveOnly #= true
      alloc(dut, cd, 1, 0x100, 0x22222222L, Size.LONG,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      if(unfilled) dut.io.reserveOnly #= false
      setQuery(dut, 3, 0x100, Size.LONG); sleep(1)
      if(unfilled) assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean,
        "old committed data escaped through a younger unfilled overwrite")
      else assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0x22222222L,
        "forwarding selected an old committed ROB-index generation")
    }
  }

  test("SQ allocation rank survives all physical head positions and stale committed generations", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, reserveLateStore = true,
      forwardOnPublish = true)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      val random = new scala.util.Random(0x53514147L)
      val heads = scala.collection.mutable.Set.empty[Int]
      for(round <- 0 until 64) {
        heads += dut.head.toInt
        val robHead = random.nextInt(32)
        val query = (robHead + 10) & 31
        for((id, data) <- Seq(query -> 0x11111111L, ((query - 1) & 31) -> 0x33333333L)) {
          alloc(dut, cd, id, 0x100, data, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
          commit(dut, cd, id)
        }
        dut.io.robHeadIn #= robHead; dut.io.robHeadValidIn #= true
        val liveId = (robHead + 1) & 31
        val partial = round % 3 == 0
        val unfilled = round % 2 == 0
        val data = random.nextLong() & 0xffffffffL
        sleep(1)
        val slot = dut.io.allocSlot.toInt
        dut.io.reserveOnly #= unfilled
        alloc(dut, cd, liveId, if(partial) 0x101 else 0x100, data,
          if(partial) Size.BYTE else Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
        dut.io.reserveOnly #= false
        setQuery(dut, query, 0x100, Size.LONG); sleep(1)
        assert(dut.io.fwd.rsp.hit.toBoolean == (!partial && !unfilled))
        assert(dut.io.fwd.rsp.stall.toBoolean == (partial || unfilled))
        if(!partial && !unfilled) assert(dut.io.fwd.rsp.data.toLong == data)
        if(unfilled) {
          dut.io.publish.valid #= true
          dut.io.publish.slot #= slot; dut.io.publish.robId #= liveId; dut.io.publish.data #= data
          sleep(1)
          assert(dut.io.fwd.rsp.hit.toBoolean == !partial)
          if(!partial) assert(dut.io.fwd.rsp.data.toLong == data)
          cd.waitSampling(); dut.io.publish.valid #= false
        }
        commit(dut, cd, liveId)
        dut.io.drain.ready #= true
        var outstanding = 0; var acceptedCount = 0
        for(_ <- 0 until 100) {
          sleep(1)
          val accepted = dut.io.drain.valid.toBoolean
          val ack = outstanding > 0
          dut.io.drainAck #= ack
          cd.waitSampling()
          if(accepted) acceptedCount += 1
          outstanding += (if(accepted) 1 else 0) - (if(ack) 1 else 0)
        }
        dut.io.drainAck #= false; dut.io.drain.ready #= false; sleep(1)
        assert(dut.io.empty.toBoolean && outstanding == 0 && acceptedCount == 3)
      }
      assert(heads.size == 8, "test never exercised every physical SQ head position")
    }
  }

  for(enabled <- Seq(false, true)) test(s"publication-edge forwarding byte model enabled=$enabled", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, subwordForwarding = true,
      reserveLateStore = true, forwardOnPublish = enabled)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      dut.io.robHeadIn #= 30; dut.io.robHeadValidIn #= true
      val random = new scala.util.Random(0x50554246L)
      val queries = (0 to 3).map(_ -> Size.BYTE) ++ (0 to 2).map(_ -> Size.WORD) ++ Seq(0 -> Size.LONG)
      for(round <- 0 until 32; (offset, size) <- queries) {
        dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false
        sleep(1)
        val slot = dut.io.allocSlot.toInt
        dut.io.reserveOnly #= true
        alloc(dut, cd, 31, 0x100, 0xdeadbeefL, Size.LONG,
          cacheMode = m68k040.cache.CacheMode.COPYBACK)
        dut.io.reserveOnly #= false
        setQuery(dut, 1, 0x100 + offset, size) // live ROB age crosses index zero
        sleep(1)
        assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
        val data = random.nextLong() & 0xffffffffL
        val bytes = if(size == Size.BYTE) 1 else if(size == Size.WORD) 2 else 4
        val expected = (0 until bytes).foldLeft(0L)((v, b) =>
          (v << 8) | ((data >>> (24 - 8 * (offset + b))) & 255))
        dut.io.publish.valid #= true
        dut.io.publish.slot #= slot; dut.io.publish.robId #= 31; dut.io.publish.data #= data
        sleep(1) // deliberately before the publication clock edge
        assert(dut.io.fwd.rsp.hit.toBoolean == enabled)
        assert(dut.io.fwd.rsp.stall.toBoolean == !enabled)
        assert(dut.publishForwardHit.toBoolean == enabled)
        if(enabled) assert(dut.io.fwd.rsp.data.toLong == expected)
        dut.io.fwd.query.inhibited #= true; sleep(1)
        assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.serial.toBoolean)
        dut.io.fwd.query.inhibited #= false
        cd.waitSampling(); dut.io.publish.valid #= false; sleep(1)
        assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == expected)
        assert(!dut.publishForwardHit.toBoolean)
      }
    }
  }

  test("publication-edge forwarding preserves youngest overlap and flush cancellation", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, reserveLateStore = true,
      forwardOnPublish = true)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      for(youngPartial <- Seq(false, true); youngUnfilled <- Seq(false, true)) {
        dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false; sleep(1)
        val oldSlot = dut.io.allocSlot.toInt
        dut.io.reserveOnly #= true
        alloc(dut, cd, 2, 0x100, 0, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
        dut.io.reserveOnly #= youngUnfilled
        alloc(dut, cd, 3, if(youngPartial) 0x101 else 0x100, 0x11223344L,
          if(youngPartial) Size.BYTE else Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
        dut.io.reserveOnly #= false
        setQuery(dut, 4, 0x100, Size.LONG)
        dut.io.publish.valid #= true
        dut.io.publish.slot #= oldSlot; dut.io.publish.robId #= 2; dut.io.publish.data #= 0x89abcdefL
        sleep(1)
        assert(!dut.publishForwardHit.toBoolean, "older publication overrode younger overlap")
        assert(dut.io.fwd.rsp.hit.toBoolean == (!youngPartial && !youngUnfilled))
        if(dut.io.fwd.rsp.hit.toBoolean) assert(dut.io.fwd.rsp.data.toLong == 0x11223344L)
        else assert(dut.io.fwd.rsp.stall.toBoolean)
        cd.waitSampling(); dut.io.publish.valid #= false
      }
      dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false; sleep(1)
      val slot = dut.io.allocSlot.toInt
      dut.io.reserveOnly #= true
      alloc(dut, cd, 2, 0x100, 0, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
      dut.io.reserveOnly #= false
      dut.io.publish.valid #= true
      dut.io.publish.slot #= slot; dut.io.publish.robId #= 2; dut.io.publish.data #= 0x89abcdefL
      // Independently translated second query fragment overlaps the publication.
      setQuery(dut, 4, 0x20f, Size.LONG, splitB = true, paddrB = 0x100)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
      setQuery(dut, 4, 0x100, Size.LONG); dut.io.flush #= true; sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.publishForwardHit.toBoolean)
      cd.waitSampling(); dut.io.publish.valid #= false; dut.io.flush #= false; sleep(1)
      assert(dut.io.empty.toBoolean && !dut.io.fwd.rsp.hit.toBoolean)
    }
  }

  test("late SQ reservations consume capacity, block forwarding, and fill in place", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, subwordForwarding = true,
      reserveLateStore = true)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      val random = new scala.util.Random(0x52535645L)
      for(round <- 0 until 32) {
        dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false
        val slots = (0 until 8).map { i =>
          sleep(1)
          val slot = dut.io.allocSlot.toInt
          dut.io.reserveOnly #= true
          alloc(dut, cd, 2 + i, 0x100, 0xdeadbeefL, Size.LONG,
            cacheMode = m68k040.cache.CacheMode.COPYBACK)
          dut.io.reserveOnly #= false
          slot
        }
        sleep(1)
        assert(dut.io.full.toBoolean && !dut.io.empty.toBoolean)
        assert(!dut.io.drain.valid.toBoolean)
        setQuery(dut, 12, 0x101, Size.WORD)
        sleep(1)
        assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
        // Older data must never escape through the youngest unfilled overwrite.
        for(i <- 0 until 8) {
          val data = random.nextLong() & 0xffffffffL
          dut.io.publish.valid #= true
          dut.io.publish.slot #= slots(i); dut.io.publish.robId #= 2 + i
          dut.io.publish.data #= data
          cd.waitSampling(); dut.io.publish.valid #= false; sleep(1)
          assert(dut.io.full.toBoolean, "fill must not allocate or release a second slot")
          if(i == 7) {
            assert(dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean)
            assert(dut.io.fwd.rsp.data.toLong == ((data >>> 8) & 0xffffL))
          } else assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
        }
      }
    }
  }

  test("late SQ fill can coincide with allocation, and flush cancels its synchronous owner", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, reserveLateStore = true)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false
      dut.io.reserveOnly #= true
      alloc(dut, cd, 2, 0x100, 0, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
      dut.io.reserveOnly #= false
      dut.io.publish.valid #= true
      dut.io.publish.slot #= 0; dut.io.publish.robId #= 2; dut.io.publish.data #= 0x12345678L
      alloc(dut, cd, 3, 0x200, 0xabcdef01L, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
      dut.io.publish.valid #= false
      setQuery(dut, 8, 0x100, Size.LONG); sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0x12345678L)
      setQuery(dut, 8, 0x200, Size.LONG); sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0xabcdef01L)
      commit(dut, cd, 2)
      dut.io.reserveOnly #= true
      alloc(dut, cd, 4, 0x100, 0, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
      dut.io.reserveOnly #= false
      dut.io.publish.valid #= true
      dut.io.publish.slot #= 2; dut.io.publish.robId #= 4; dut.io.publish.data #= 0xfedcba98L
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false; dut.io.publish.valid #= false
      setQuery(dut, 8, 0x100, Size.LONG); sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0x12345678L,
        "flush must retain the committed predecessor, canceling both younger entries")
      // Reuse the rolled-back tail with a new synchronous owner, not an old reply.
      dut.io.reserveOnly #= true; sleep(1)
      val newSlot = dut.io.allocSlot.toInt
      alloc(dut, cd, 5, 0x100, 0, Size.LONG, cacheMode = m68k040.cache.CacheMode.COPYBACK)
      dut.io.reserveOnly #= false
      setQuery(dut, 8, 0x100, Size.LONG); sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
      dut.io.publish.valid #= true
      dut.io.publish.slot #= newSlot; dut.io.publish.robId #= 5; dut.io.publish.data #= 0x55667788L
      cd.waitSampling(); dut.io.publish.valid #= false
      commit(dut, cd, 5)
      val seen = scala.collection.mutable.ArrayBuffer.empty[Long]
      cd.onSamplings {
        if(dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
          seen += dut.io.drain.data.toLong
      }
      // Count every accepted beat, including adjacent-cycle pipelined commands.
      // The legacy waitSamplingWhere helper misses the second of such a pair.
      dut.io.drain.ready #= true
      var outstanding = 0
      for(_ <- 0 until 100) {
        sleep(1)
        val accepted = dut.io.drain.valid.toBoolean
        val ack = outstanding > 0
        dut.io.drainAck #= ack
        cd.waitSampling()
        outstanding += (if(accepted) 1 else 0) - (if(ack) 1 else 0)
      }
      dut.io.drainAck #= false; sleep(1)
      assert(dut.io.empty.toBoolean, "both committed filled entries must drain within 100 cycles")
      assert(seen.toSeq == Seq(0x12345678L, 0x55667788L))
    }
  }

  test("optional subword forwarding matches big-endian byte model at every offset", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, subwordForwarding = true)).doSim { dut =>
      val cd = initDut(dut)
      val random = new scala.util.Random(0x53514657L)
      for(round <- 0 until 64) {
        dut.io.flush #= true
        cd.waitSampling()
        dut.io.flush #= false
        val base = 0x100L + 4 * random.nextInt(16)
        val data = random.nextLong() & 0xffffffffL
        alloc(dut, cd, 4, base, data, Size.LONG,
          cacheMode = m68k040.cache.CacheMode.COPYBACK)
        for(offset <- -1 to 4; (size, bytes) <- Seq(Size.BYTE -> 1, Size.WORD -> 2, Size.LONG -> 4)) {
          setQuery(dut, 6, base + offset, size,
            splitB = ((base + offset) & 15) + bytes > 16,
            paddrB = ((base + offset) & ~15L) + 16)
          sleep(1)
          val contained = offset >= 0 && offset + bytes <= 4
          val overlap = offset < 4 && offset + bytes > 0
          assert(dut.io.fwd.rsp.hit.toBoolean == contained,
            s"round=$round offset=$offset bytes=$bytes hit")
          assert(dut.io.fwd.rsp.stall.toBoolean == (overlap && !contained))
          if(contained) {
            val expected = (0 until bytes).foldLeft(0L) { (v, b) =>
              (v << 8) | ((data >>> (24 - 8 * (offset + b))) & 255)
            }
            assert(dut.io.fwd.rsp.data.toLong == expected,
              f"offset=$offset bytes=$bytes data=$data%x expected=$expected%x")
          }
        }
      }
    }
  }

  test("subword forwarding preserves younger-overwrite, wrap, device and flush rules", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, subwordForwarding = true)).doSim { dut =>
      val cd = initDut(dut)
      val wrap = 1 << dut.io.robHeadIn.getWidth
      dut.io.robHeadIn #= wrap - 4
      alloc(dut, cd, wrap - 2, 0x100, 0x89abcdefL, Size.LONG,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      setQuery(dut, 1, 0x101, Size.WORD)
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0xabcd)
      alloc(dut, cd, 0, 0x102, 0x55, Size.BYTE,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      setQuery(dut, 1, 0x101, Size.WORD)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean,
        "younger partial overwrite must block the older covering store")
      setQuery(dut, 1, 0x102, Size.BYTE)
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0x55)
      setQuery(dut, wrap - 1, 0x102, Size.BYTE)
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0xcd,
        "query preceding the overwrite must see the older longword")
      setQuery(dut, 1, 0x100, Size.BYTE, inhibited = true)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.serial.toBoolean)
      dut.io.drain.ready #= false
      commit(dut, cd, wrap - 2)
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      dut.io.robHeadIn #= 2
      setQuery(dut, 3, 0x102, Size.BYTE)
      sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.data.toLong == 0xcd,
        "committed producer survives flush and remains forwardable")
      alloc(dut, cd, 2, 0x200, 0x55, Size.BYTE,
        cacheMode = m68k040.cache.CacheMode.INHIBITED)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.serial.toBoolean,
        "nonoverlapping older device store must suppress subword forwarding")
    }
  }

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
    dut.io.alloc.payload.validB #= false
    dut.io.alloc.payload.paddrB #= 0
    dut.io.alloc.payload.vaddrB #= 0
    dut.io.alloc.payload.nbytesB #= 0
    dut.io.alloc.payload.cacheMode #= cacheMode
    dut.io.alloc.payload.cacheModeB #= cacheMode
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

  def setQuery(dut: StoreQueue, robId: Int, paddr: Long, size: SpinalEnumElement[Size.type],
               inhibited: Boolean = false, splitB: Boolean = false, paddrB: Long = 0): Unit = {
    dut.io.fwd.query.robId #= robId
    dut.io.fwd.query.paddr #= paddr
    dut.io.fwd.query.size #= size
    dut.io.fwd.query.splitB #= splitB
    dut.io.fwd.query.paddrB #= paddrB
    dut.io.fwd.query.inhibited #= inhibited
    dut.io.barrier.robId #= robId
  }

  test("inhibited accesses serialize across non-overlapping device ports", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)

      // A device write and its readback/status port need not overlap or share a
      // cache line.  The younger inhibited load must still wait.
      alloc(dut, cd, robId = 4, paddr = 0x50f1e800L, data = 0xf5, Size.BYTE,
        cacheMode = m68k040.cache.CacheMode.INHIBITED, precise = true)
      setQuery(dut, robId = 6, paddr = 0x50f1f800L, Size.BYTE, inhibited = true)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "device load must never SQ-forward")

      // Total ordering is bidirectional at the boundary: an ordinary younger load
      // also cannot pass an older inhibited store, and neither may forward across it.
      setQuery(dut, robId = 6, paddr = 0x3000L, Size.LONG, inhibited = false)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean,
        "ordinary load must not forward across an older inhibited store")

      // WHERE THE ORDERING NOW LIVES.  It is deliberately NOT expressed as a
      // forwarding stall here.  Driving it as `rsp.stall` made the LS-EU re-query
      // from P4 every cycle until the store drained -- a wait that is not
      // self-resolving, because the spinning load holds resources the drain can
      // need, and the core hung on real hardware (2026-08-22, `TST.B` of a VIA
      // register).  The barrier is instead published as a whole-ring property that
      // the LS-EU consumes ONCE at its load-launch gate; see `p4LaunchOk` in
      // LsEuPlugin and the two device-ordering tests in LsEuFastPreciseSpec.
      assert(dut.io.barrier.olderInhibitedStore.toBoolean,
        "an OLDER resident device store must raise the barrier for this load")
      // And the age qualifier is real: a store that is YOUNGER than the querying load
      // must NOT gate it, or a younger store could hold an older load forever.
      dut.io.barrier.robId #= 1
      sleep(1)
      assert(!dut.io.barrier.olderInhibitedStore.toBoolean,
        "a YOUNGER device store must not raise the barrier for an older load")
    }
  }

  def initDut(dut: StoreQueue): ClockDomain = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    dut.io.alloc.valid #= false
    if(dut.io.reserveOnly != null) {
      dut.io.reserveOnly #= false
      dut.io.publish.valid #= false
      dut.io.publish.slot #= 0; dut.io.publish.robId #= 0; dut.io.publish.data #= 0
    }
    dut.io.commit.valid #= false
    dut.io.commitB.valid #= false; dut.io.commitB.payload #= 0
    dut.io.flush #= false
    dut.io.drain.ready #= true
    dut.io.drainAck #= false
    dut.io.drainErr #= false
    dut.io.robHeadIn #= 0; dut.io.robHeadValidIn #= false; dut.io.irqPreemptPendingIn #= false
    setQuery(dut, 0, 0, Size.LONG)
    cd.waitSampling(3)
    cd
  }

  /** Model a one-cycle D-cache completion for every accepted drain command. */
  def forkDrainAck(dut: StoreQueue, cd: ClockDomain): Unit = fork {
    while (true) {
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
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

  // LS-cluster review finding P5 (this task -- supersedes the old "Task P4.4 Step 6
  // copyback re-audit" test that used to live here and asserted the OPPOSITE of what
  // this test now proves; see the `sameLine` decl comment in StoreQueue.scala for the
  // full hazard-coverage argument for why the old conclusion was safe-but-unnecessarily
  // conservative). A same-line, NON-overlapping younger load must NOT stall behind an
  // older, still-undrained COPYBACK store: COPYBACK's own eventual drain (RMW-on-hit or
  // write-allocate-on-miss) always self-heals the array, so the WRITETHROUGH-specific
  // "permanently stale line" hole this stall exists for does not apply.
  test("same-line, non-overlapping younger load does NOT stall behind an older " +
       "undrained COPYBACK store (LS-cluster review finding P5)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // An OLDER COPYBACK store at 0x102..0x103 (cache line 0x100..0x10F).
      alloc(dut, cd, robId = 4, paddr = 0x102, data = 0xBEEFL, Size.WORD,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      // A younger load at 0x108 -- no BYTE overlap with the store's own range at all
      // (so it would MISS any byte-forward), same cache line -- must NOT stall, since
      // this is exactly the case the P5 finding narrows: COPYBACK's own drain (whichever
      // arm it takes) always leaves the array holding the store's bytes, so there is no
      // "cache a permanently stale line" hole for a racing load-refill to fall into.
      setQuery(dut, robId = 6, paddr = 0x108, Size.BYTE)
      cd.waitSampling()
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "no byte overlap -> not a forward")
      assert(!dut.io.fwd.rsp.stall.toBoolean,
        "same-cache-line, non-overlapping, older COPYBACK entry -> must NOT stall (P5 fix)")
      // Still un-drained: prove the entry is genuinely still resident (not merely
      // vacuously absent), so the negative assertion above is actually exercising the
      // narrowed condition and not just an empty queue.
      assert(!dut.io.empty.toBoolean, "the COPYBACK store must still be resident/undrained here")
      // Drain the store (commit + drain + drainAck) and confirm nothing regresses post-drain.
      commit(dut, cd, robId = 4)
      cd.waitSampling()
      assert(dut.io.drain.valid.toBoolean, "committed COPYBACK entry must present for drain")
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
      setQuery(dut, robId = 6, paddr = 0x108, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.stall.toBoolean, "post-drain, still no stall")
      cd.waitSampling(2)
    }
  }

  // Companion case to the one above: a GENUINE byte-level overlap against an older
  // undrained COPYBACK store must still stall -- via the untouched `anyPartial`/`overlap`
  // RAW-hazard mechanism, NOT via `sameLine` (which the P5 fix now excludes for COPYBACK).
  // This is the case that would silently break if the P5 fix had accidentally gated the
  // wrong condition (e.g. the overlap/full/partial terms instead of only the line terms).
  test("genuine byte-overlap against an older undrained COPYBACK store still stalls " +
       "(via anyPartial, unaffected by the P5 sameLine fix)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // An OLDER COPYBACK store at 0x102..0x103 (WORD).
      alloc(dut, cd, robId = 4, paddr = 0x102, data = 0xBEEFL, Size.WORD,
        cacheMode = m68k040.cache.CacheMode.COPYBACK)
      // A younger load at 0x103 -- a genuine sub-range BYTE overlap with the store's own
      // range -- must still stall (this is a real RAW hazard, not the refill-stale-line
      // hazard `sameLine` used to (over-)cover).
      setQuery(dut, robId = 6, paddr = 0x103, Size.BYTE)
      cd.waitSampling()
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "sub-range byte is not a full forward")
      assert(dut.io.fwd.rsp.stall.toBoolean,
        "genuine byte overlap against an older undrained COPYBACK store -> must still stall")
      // Drain and confirm the hazard clears once the store is gone.
      commit(dut, cd, robId = 4)
      cd.waitSampling()
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
      setQuery(dut, robId = 6, paddr = 0x103, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.stall.toBoolean, "once drained, the overlap hazard must clear")
      cd.waitSampling(2)
    }
  }

  // WT-pipelining task: `sendPipelined` (StoreQueue.scala) now covers non-precise
  // WRITETHROUGH the same way it already covered COPYBACK -- `io.drain.valid`
  // presents a committed entry off `sendCommitted` alone (no `sendAtHead`/
  // `noAccepted`), so a SECOND committed WT entry can be PRESENTED for drain while
  // the FIRST is still "accepted" (drained but not yet acked). This is the SQ-side
  // half of the pipelining change; DcachePluginSpec-adjacent coverage (DcacheSpec)
  // proves the DcachePlugin-side admission/AXI-overlap half.
  test("WT-pipelining: a second committed non-precise WRITETHROUGH entry presents " +
       "for drain while the first is still accepted (not yet acked) -- was sendAtHead-gated before this task",
       VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)   // io.drain.ready held True throughout (initDut's default)
      alloc(dut, cd, robId = 4, paddr = 0x200, data = 0x1111L, Size.LONG,
        cacheMode = m68k040.cache.CacheMode.WRITETHROUGH, precise = false)
      alloc(dut, cd, robId = 5, paddr = 0x300, data = 0x2222L, Size.LONG,
        cacheMode = m68k040.cache.CacheMode.WRITETHROUGH, precise = false)
      // drainAck stays LOW (initDut's default) throughout this phase -- nothing
      // pops, so any entry that gets SENT (drain.fire, which `io.drain.ready`
      // held True lets happen the moment it presents) stays counted in
      // `acceptedHalves` until explicitly acked below.
      commit(dut, cd, robId = 4)
      commit(dut, cd, robId = 5)
      cd.waitSampling(4)   // let both committed entries clear the send cursor

      // Before this task, `sendSerialReady` required `sendAtHead && noAccepted` --
      // the SECOND WT entry could not even be PRESENTED (let alone sent) until the
      // FIRST was fully acked, so `acceptedHalves` could never exceed 1 for a
      // WRITETHROUGH stream. After this task (`sendPipelined` covers WRITETHROUGH
      // exactly like COPYBACK), both commited entries are sent back-to-back and
      // BOTH sit "accepted" (sent, unacked) simultaneously -- the direct SQ-side
      // proof of pipelining.
      assert(dut.acceptedHalves.toBigInt == 2,
        s"both committed WT entries must be simultaneously accepted (sent, unacked): got ${dut.acceptedHalves.toBigInt}")
      assert(dut.head.toBigInt == 0, "nothing has been acked yet -- robId=4 (index 0) is still the oldest resident entry")
      assert(dut.robIds(0).toBigInt == 4 && dut.robIds(1).toBigInt == 5,
        "both entries remain resident, in their original ring slots, until acked")

      // The SQ's own `drainAck` contract is "always the oldest accepted half"
      // (StoreQueue.scala doc comment) -- acking now must pop robId=4 first, even
      // though robId=5 was ALSO already sent, proving send order == ack order
      // (program order preserved under pipelining).
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
      assert(dut.head.toBigInt == 1, "first ack pops the OLDEST accepted half (robId=4), not robId=5")
      assert(dut.acceptedHalves.toBigInt == 1)

      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
      assert(dut.io.empty.toBoolean, "both entries popped, strictly in program (send/ack) order")
    }
  }

  // WT-pipelining task (point 3c of the design brief): the `sameLine` load-forward
  // hazard holds for a pipelined, non-precise WRITETHROUGH store exactly as
  // conservatively as it always has (WRITETHROUGH is NOT affected by the LS-cluster
  // review finding P5 fix two tests above, which narrows this stall for COPYBACK
  // only) -- the hazard is driven purely by SQ residency (`valids`/`committed`/age),
  // never by drain/AXI timing, so a WT entry that has ALREADY been sent to
  // DcachePlugin (and may have an AXI write in flight) still correctly stalls a
  // same-line younger load until its OWN terminal ack pops it. This test's body is
  // UNCHANGED by the P5 fix -- only this comment was updated to stop pointing at the
  // old (now-superseded) COPYBACK test's conclusion.
  test("same-line stall still holds an older undrained entry under a pipelined " +
       "(non-precise) WRITETHROUGH store, even after it has been sent/accepted",
       VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // An OLDER non-precise WRITETHROUGH store at 0x102..0x103 (line 0x100..0x10F).
      alloc(dut, cd, robId = 4, paddr = 0x102, data = 0xBEEFL, Size.WORD,
        cacheMode = m68k040.cache.CacheMode.WRITETHROUGH, precise = false)
      commit(dut, cd, robId = 4)
      cd.waitSampling()
      assert(dut.io.drain.valid.toBoolean, "committed WT entry must present for drain")
      cd.waitSampling()   // accept it -- now "accepted, unacked" (an AXI write would be in flight here)

      // A younger load, same cache line, no byte overlap -- must still stall even
      // though the store has ALREADY been sent to DcachePlugin (pipelined) and is
      // sitting in the "accepted, unacked" window a real outstanding AXI write
      // occupies.
      setQuery(dut, robId = 6, paddr = 0x108, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "no byte overlap -> not a forward")
      assert(dut.io.fwd.rsp.stall.toBoolean,
        "same-cache-line older WT entry, sent but still unacked -> must stall")

      // Only the TERMINAL ack (matching a real AXI B) clears it.
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
      setQuery(dut, robId = 6, paddr = 0x108, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.stall.toBoolean, "once acked, the same-line hazard must clear")
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

  // ---- Task P2.4: precise-path at-head drain trigger, sqCompletion/sqFaultCompletion,
  // error-pop, preciseDrainBusy. Directed per the design doc §4.1 mechanism. Note:
  // robHeadValidIn/robHeadIn/irqPreemptPendingIn/drainErr are exercised DIRECTLY here
  // (StoreQueue standalone) -- they stay dead-wired (False) from LsEuPlugin's side
  // until Task P2.5, so these behaviors are NOT yet reachable through the full core. ----

  test("P2.4: precise head at ROB head drains WITHOUT commit; ack-OK drives sqCompletion", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      alloc(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG,
        vaddr = 0x20000100L, precise = true)
      sleep(1)
      assert(!dut.io.drain.valid.toBoolean, "precise head must not drain before robHeadValidIn")
      assert(!dut.committed(dut.head.toInt).toBoolean, "entry is genuinely uncommitted")
      // robId reaches the (non-speculative) ROB head -> drains WITHOUT committed ever set
      dut.io.robHeadIn #= 4
      dut.io.robHeadValidIn #= true
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x100, "drain presents the precise head entry")
      assert(!dut.committed(dut.head.toInt).toBoolean, "still uncommitted at the moment of drain-issue")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      sleep(1)
      assert(dut.io.sqCompletion.valid.toBoolean, "ack-OK must drive sqCompletion")
      assert(dut.io.sqCompletion.payload.toInt == 4, "sqCompletion carries the drained robId")
      assert(!dut.io.sqFaultCompletion.valid.toBoolean, "no error -> no fault completion")
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "the only entry popped -> queue empty")
      cd.waitSampling(2)
    }
  }

  test("P2.4: precise-path bus error drives sqCompletion+sqFaultCompletion with the failing slot's vaddr, pops terminally", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      alloc(dut, cd, robId = 5, paddr = 0x400, data = 0x11112222L, Size.LONG,
        vaddr = 0x30004000L, supervisor = true, precise = true)
      dut.io.robHeadIn #= 5
      dut.io.robHeadValidIn #= true
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      dut.io.drainErr #= true
      sleep(1)
      assert(dut.io.sqCompletion.valid.toBoolean,
        "an errored drain must STILL fire sqCompletion -- the ROB's completes-required-even-for-a-fault contract")
      assert(dut.io.sqCompletion.payload.toInt == 5)
      assert(dut.io.sqFaultCompletion.valid.toBoolean, "AXI B error must drive sqFaultCompletion")
      assert(dut.io.sqFaultCompletion.payload.robId.toInt == 5)
      assert(dut.io.sqFaultCompletion.payload.faultAddr.toLong == 0x30004000L,
        s"faultAddr must be the failing slot's LOGICAL address: ${dut.io.sqFaultCompletion.payload.faultAddr.toLong.toHexString}")
      assert(dut.io.sqFaultCompletion.payload.write.toBoolean, "a store fault is always a write")
      assert(dut.io.sqFaultCompletion.payload.sizeBits.toInt == 2, "LONG-encoded size")
      assert(dut.io.sqFaultCompletion.payload.supervisor.toBoolean, "supervisor bit carried through")
      assert(!dut.io.sqFaultCompletion.payload.atc.toBoolean, "a physical bus error is never ATC/MMU")
      cd.waitSampling()
      dut.io.drainAck #= false
      dut.io.drainErr #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "terminal error-pop must not leave an orphan entry resident")
      cd.waitSampling(2)
    }
  }

  test("P2.4: split precise store faulting in slot B reports slot B's OWN vaddr (not slot A's)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val a = dut.io.alloc
      a.valid #= true
      a.payload.robId #= 7
      a.payload.paddr #= 0x1000; a.payload.vaddr #= 0x21000000L
      a.payload.data #= 0; a.payload.size #= Size.LONG
      a.payload.nbytesA #= 2; a.payload.useStrbA #= true
      a.payload.validB #= true
      a.payload.paddrB #= 0x2000; a.payload.vaddrB #= 0x22000000L
      a.payload.nbytesB #= 2
      a.payload.cacheMode #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.cacheModeB #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.supervisor #= false
      a.payload.precise #= true
      cd.waitSampling()
      a.valid #= false

      dut.io.robHeadIn #= 7
      dut.io.robHeadValidIn #= true
      // slot A drains cleanly (no error) -- entry does NOT pop yet (validB -> phase B next)
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x1000, "slot A presented first")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      sleep(1)
      assert(!dut.io.sqFaultCompletion.valid.toBoolean, "slot A itself did not error")
      assert(!dut.io.sqCompletion.valid.toBoolean,
        "slot A's ack must NOT fire sqCompletion -- the entry hasn't popped yet (drainPhaseB just advanced)")
      cd.waitSampling()
      dut.io.drainAck #= false
      // slot B now presented -> fault it
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x2000, "slot B presented next (atomic two-half drain)")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      dut.io.drainErr #= true
      sleep(1)
      assert(dut.io.sqFaultCompletion.valid.toBoolean)
      assert(dut.io.sqFaultCompletion.payload.faultAddr.toLong == 0x22000000L,
        s"faultAddr must be slot B's OWN vaddr, not slot A's: ${dut.io.sqFaultCompletion.payload.faultAddr.toLong.toHexString}")
      cd.waitSampling()
      dut.io.drainAck #= false; dut.io.drainErr #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "terminal error-pop must not leave an orphan entry resident")
      cd.waitSampling(2)
    }
  }

  test("P2.4: split precise store cleanly draining fires sqCompletion exactly once, on slot B's terminal ack", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val a = dut.io.alloc
      a.valid #= true
      a.payload.robId #= 8
      a.payload.paddr #= 0x1000; a.payload.vaddr #= 0x21000000L
      a.payload.data #= 0; a.payload.size #= Size.LONG
      a.payload.nbytesA #= 2; a.payload.useStrbA #= true
      a.payload.validB #= true
      a.payload.paddrB #= 0x2000; a.payload.vaddrB #= 0x22000000L
      a.payload.nbytesB #= 2
      a.payload.cacheMode #= m68k040.cache.CacheMode.COPYBACK
      a.payload.cacheModeB #= m68k040.cache.CacheMode.INHIBITED
      a.payload.supervisor #= false
      a.payload.precise #= true
      cd.waitSampling()
      a.valid #= false

      dut.io.robHeadIn #= 8
      dut.io.robHeadValidIn #= true
      // slot A drains cleanly (no error) -- entry does NOT pop yet (validB -> phase B next)
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x1000, "slot A presented first")
      assert(dut.io.drain.payload.cacheMode.toEnum == m68k040.cache.CacheMode.COPYBACK,
        "slot A must retain its own translated cache mode")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      sleep(1)
      assert(!dut.io.sqCompletion.valid.toBoolean,
        "slot A's clean ack must NOT fire sqCompletion -- the entry hasn't popped yet")
      cd.waitSampling()
      dut.io.drainAck #= false
      // slot B now presented -> ack it cleanly too
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x2000, "slot B presented next (atomic two-half drain)")
      assert(dut.io.drain.payload.cacheMode.toEnum == m68k040.cache.CacheMode.INHIBITED,
        "slot B must retain its own translated cache mode")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      sleep(1)
      assert(dut.io.sqCompletion.valid.toBoolean,
        "slot B's terminal (pop) ack must fire sqCompletion -- this is the only cycle it should fire")
      assert(dut.io.sqCompletion.payload.toInt == 8, "sqCompletion carries the drained robId")
      assert(!dut.io.sqFaultCompletion.valid.toBoolean, "no error -> no fault completion")
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(!dut.io.sqCompletion.valid.toBoolean, "sqCompletion must not still be asserted the cycle after the pop")
      assert(dut.io.empty.toBoolean, "both slots drained -> queue empty")
      cd.waitSampling(2)
    }
  }

  test("P2.4: irqPreemptPendingIn blocks a not-yet-launched precise drain", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      alloc(dut, cd, robId = 9, paddr = 0x500, data = 0x55555555L, Size.LONG,
        vaddr = 0x40005000L, precise = true)
      dut.io.robHeadIn #= 9
      dut.io.robHeadValidIn #= true
      dut.io.irqPreemptPendingIn #= true
      sleep(1)
      assert(!dut.io.drain.valid.toBoolean, "a pending preempt must block launching a precise drain")
      cd.waitSampling(3)
      assert(!dut.io.drain.valid.toBoolean, "still blocked while irqPreemptPendingIn stays high")
      // preempt clears -> the drain launches
      dut.io.irqPreemptPendingIn #= false
      sleep(1)
      assert(dut.io.drain.valid.toBoolean, "drain must launch once the preempt-pending input clears")
      cd.waitSampling()   // present -> held (drainBusy now registered True)
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      cd.waitSampling(2)
    }
  }

  test("P2.4: a LAUNCHED precise drain (registered preciseDrainBusy) is unaffected by irqPreemptPendingIn going true mid-drain", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      alloc(dut, cd, robId = 11, paddr = 0x600, data = 0x66666666L, Size.LONG,
        vaddr = 0x50006000L, precise = true)
      assert(!dut.io.preciseDrainBusy.toBoolean, "not busy before launch")
      dut.io.robHeadIn #= 11
      dut.io.robHeadValidIn #= true
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)   // launch cycle (headPreciseReady && !drainBusy)
      cd.waitSampling()   // one cycle after launch -> preciseDrainBusyReg registers True
      sleep(1)
      assert(dut.io.preciseDrainBusy.toBoolean, "preciseDrainBusy must be asserted while the drain is in flight")
      // NOW an interrupt/trace becomes pending mid-drain -- must NOT cancel/un-launch it.
      dut.io.irqPreemptPendingIn #= true
      cd.waitSampling(2)
      sleep(1)
      assert(dut.io.preciseDrainBusy.toBoolean, "an in-flight drain must not be affected by a mid-drain preempt-pending")
      assert(!dut.io.drain.valid.toBoolean, "must not re-present while busy/held")
      // ack it -> completes normally despite irqPreemptPendingIn still being true
      dut.io.drainAck #= true
      sleep(1)
      assert(dut.io.sqCompletion.valid.toBoolean, "the launched drain must resolve normally despite the preempt-pending input")
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "entry popped after the ack")
      // preciseDrainBusy drops one cycle after resolution (design doc: held one extra
      // cycle past ack for the ack-OK-to-retire handshake seam).
      assert(dut.io.preciseDrainBusy.toBoolean, "preciseDrainBusy stays held the cycle immediately after ack")
      cd.waitSampling()
      sleep(1)
      assert(!dut.io.preciseDrainBusy.toBoolean, "preciseDrainBusy drops one cycle after resolution")
      cd.waitSampling(2)
    }
  }

  // ── CPUSHL/S_DRAIN real-hardware hang investigation (BUG_calibration_word_
  // misplaced_0d00.md Part 37) ──────────────────────────────────────────────
  // Part 36 narrowed a genuine, permanent real-hardware boot stall at ROM PC
  // 0x40887126 (a CPUSHL/dbf cache-flush loop) to ExceptionUnit's S_DRAIN state's
  // `sqDrained` (== StoreQueue `io.empty`) never becoming true, and flagged this
  // exact file's OWN "phantom entry never drains" bug class (the `popsHead` fix
  // just above, for a DIFFERENT race) as the most likely explanation, asking
  // whether EVERY flush source is handled uniformly -- the same review that found
  // the RAS checkpoint gap (`683355d`) needed BOTH `RedirectService.doFlush` and
  // `FetchAlignPlugin.ftqMismatch` as restore triggers, because only one of them
  // is retire-gated.
  //
  // The `keep()` computation above squashes ANY uncommitted entry unconditionally,
  // reasoning (per this file's own §4.1 doc comments elsewhere) that a flush can
  // never race an in-flight PRECISE drain because branch-mispredict/exception
  // flushes can only originate from a RETIRING head, and an uncommitted precise
  // entry blocks retire of everything at/behind it. That argument is airtight for
  // `branchRedirect`/`exc.redirectValid` -- but `RobPlugin`'s `doFlushReg` ALSO
  // fires from `debugRecoverEnter`/`debugPcApply` (RobPlugin.scala:2444), neither
  // of which is retire-gated the same way (a JTAG-driven halt/step/PC-apply can
  // land on ANY cycle, including mid-drain of a precise split store that has
  // ALREADY had its slot-A half ACCEPTED by DcachePlugin -- an irrevocable,
  // already-in-flight physical bus write that cannot be un-issued).
  //
  // This test reproduces exactly that race directly against StoreQueue (no debug/
  // ROB plumbing needed -- `io.flush` is the same signal regardless of source):
  // launch a precise SPLIT store's drain, let slot A's half be ACCEPTED
  // (`io.drain.fire`) but NOT yet acked, then flush. The flush squashes
  // `valids(head)` (uncommitted), but nothing else advances `head`/`sendPtr` or
  // resets `ackPhaseB`/`acceptedHalves` -- those only change on a REAL drainAck.
  // When DcachePlugin's already-in-flight slot-A write eventually acks (it WILL --
  // the transaction was already accepted, it is not cancellable), the entry does
  // NOT pop (validBs(head) is still true, ackPhaseB was false -> the "slot A acked,
  // advance to slot B" arm fires instead), leaving `ackPhaseB` stuck True and
  // `head`/`sendPtr` PERMANENTLY parked on the now-dead, squashed index: every
  // future `sendPreciseReady`/`sendCommitted`/`sendSerialReady` check requires
  // `sendAtHead` (`sendPtr === head`) AND per-index state at that SAME dead index
  // (`valids(head)`/`precises(sendPtr)`, both now False/stale) -- so NOTHING, not
  // even a brand-new committed fast WRITETHROUGH store allocated afterward, can
  // ever drain again. `io.empty` reads true in the narrow window right after the
  // orphaned ack resolves (valids all clear, drainBusy clear) -- masking the wedge
  // -- until the NEXT store allocates and can never leave, at which point
  // `io.empty` (== `sqDrained`) is stuck false FOREVER, exactly matching the real-
  // hardware S_DRAIN hang.
  test("BUG_calibration_word Part 37: a flush racing an in-flight (accepted, unacked) " +
       "precise split-store drain half must NOT wedge the ring -- the in-flight entry " +
       "is kept and allowed to complete, same class as the popsHead fix above but a " +
       "DIFFERENT trigger", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val a = dut.io.alloc
      a.valid #= true
      a.payload.robId #= 21
      a.payload.paddr #= 0x1000; a.payload.vaddr #= 0x21000000L
      a.payload.data #= 0; a.payload.size #= Size.LONG
      a.payload.nbytesA #= 2; a.payload.useStrbA #= true
      a.payload.validB #= true
      a.payload.paddrB #= 0x2000; a.payload.vaddrB #= 0x22000000L
      a.payload.nbytesB #= 2
      a.payload.cacheMode #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.cacheModeB #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.supervisor #= false
      a.payload.precise #= true
      cd.waitSampling()
      a.valid #= false

      dut.io.robHeadIn #= 21
      dut.io.robHeadValidIn #= true
      // slot A is presented and ACCEPTED (io.drain.ready is tied true by initDut) --
      // an irrevocable bus write from DcachePlugin's point of view.
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x1000, "slot A presented first")
      cd.waitSampling()   // drainIssue registers: acceptedHalves=1, sendPhaseB=true
      sleep(1)
      assert(dut.drainBusy.toBoolean, "slot A's accepted half is outstanding (unacked)")

      // A debug-recover-class flush (or any other non-retire-gated redirect source)
      // lands HERE -- before slot A's own drainAck arrives.
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      sleep(1)
      // THE FIX: the in-flight entry must be KEPT (not squashed) -- its slot-A write
      // already left the CPU and cannot be un-issued; the ring must be allowed to
      // unwind normally instead of being abandoned mid-sequence.
      assert(dut.valids(0).toBoolean,
        "an in-flight (already-accepted) precise drain half must survive a flush -- " +
        "squashing it here is exactly what wedges the ring permanently")

      // Slot A's already-in-flight ack now arrives -- normal "advance to slot B" path.
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(!dut.io.empty.toBoolean, "slot B has not drained yet -- queue is not empty")

      // Slot B is presented and acked normally -- the entry pops via the ordinary
      // terminal-ack path, exactly like an unflushed precise split drain.
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x2000, "slot B presented next")
      cd.waitSampling()
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "both slots drained -- queue empty, entry popped cleanly")

      // Now a brand-new, ordinary committed fast store allocates and drains normally --
      // proving `head`/`sendPtr` were NOT left wedged on a dead index.
      forkDrainAck(dut, cd)
      alloc(dut, cd, robId = 22, paddr = 0x3000, data = 0x33333333L, Size.LONG)
      commit(dut, cd, robId = 22)
      cd.waitSampling(10)
      sleep(1)
      assert(dut.io.empty.toBoolean,
        "a new committed store must drain normally after the race -- the ring must " +
        "not be permanently wedged (this is exactly the real-hardware S_DRAIN/" +
        "sqDrained-stuck-forever hang)")
      cd.waitSampling(2)
    }
  }

  test("P2.4 (Step 5 regression): a flushed, never-drained precise entry does not linger (keep logic unchanged)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // precise entry, never committed, never given robHeadValidIn -> never drains.
      alloc(dut, cd, robId = 13, paddr = 0x700, data = 0x77777777L, Size.LONG,
        vaddr = 0x60007000L, precise = true)
      sleep(1)
      assert(!dut.io.drain.valid.toBoolean, "never-launched precise entry must not drain")
      // its instruction gets flushed (mispredict/exception squash upstream) before
      // ever reaching the ROB head.
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      sleep(1)
      assert(!dut.valids(0).toBoolean, "an uncommitted precise entry must squash on flush like any other uncommitted entry")
      assert(dut.io.empty.toBoolean, "queue must be empty -- no lingering orphan entry")
      assert(!dut.io.drain.valid.toBoolean, "the squashed entry must never drain")
      cd.waitSampling(2)
    }
  }

  // ── Part 127: the two defects Part 126 SS8 found by INSPECTION and left unfixed ──
  //
  // WHY THE PART 37 TEST ABOVE CANNOT SEE EITHER OF THEM (Part 126 SS8's own point):
  // it pins `io.robHeadIn` to the flushed store's own robId (21) for the whole test.
  // In the real machine the flush that keeps the entry is `RobPlugin.doFlushReg`, and
  // the ROB flush is POINTER-ONLY (`tail := head`, `head` untouched), so the entry's
  // robId is immediately re-allocatable and `io.robHeadIn` moves on. Holding it still
  // hands the kept entry the exact input it needs to finish, and hides the fact that
  // its only launch gate is a comparison against a ROB entry that no longer exists.
  //
  // Both tests below use the identical Part 37 setup and change ONE input: what the
  // ROB head does after the flush.

  test("Part 127 (b1): a flush-kept precise SPLIT entry must still launch slot B once " +
       "the ROB head has moved on -- its ROB entry is GONE, so gating slot B on " +
       "robIds(head)===robHeadIn wedges the ring forever", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val a = dut.io.alloc
      a.valid #= true
      a.payload.robId #= 21
      a.payload.paddr #= 0x1000; a.payload.vaddr #= 0x21000000L
      a.payload.data #= 0; a.payload.size #= Size.LONG
      a.payload.nbytesA #= 2; a.payload.useStrbA #= true
      a.payload.validB #= true
      a.payload.paddrB #= 0x2000; a.payload.vaddrB #= 0x22000000L
      a.payload.nbytesB #= 2
      a.payload.cacheMode #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.cacheModeB #= m68k040.cache.CacheMode.WRITETHROUGH
      a.payload.supervisor #= false
      a.payload.precise #= true
      cd.waitSampling()
      a.valid #= false

      dut.io.robHeadIn #= 21
      dut.io.robHeadValidIn #= true
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      assert(dut.io.drain.payload.paddr.toLong == 0x1000, "slot A presented first")
      cd.waitSampling()          // drainIssue registers: acceptedHalves=1, sendPhaseB=true
      sleep(1)
      assert(dut.drainBusy.toBoolean, "slot A's accepted half is outstanding (unacked)")

      // A non-retire-gated flush (debugRecoverEnter / debugPcApply -- a JTAG halt or
      // step) lands on this cycle.
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      // THE ONE CHANGED INPUT: the ROB flush destroyed robId 21's entry and the ROB
      // moved on. Anything may now sit at the head -- including, later, a brand-new
      // instruction that inherits id 21.
      dut.io.robHeadIn #= m68k040.TestRobIds.otherThan(21)
      dut.io.robHeadValidIn #= true
      sleep(1)
      assert(dut.valids(0).toBoolean, "Part 37's keep rule still applies -- the entry survives")

      // Slot A's already-in-flight ack arrives (advance-to-slot-B path).
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(!dut.io.empty.toBoolean, "slot B has not drained yet")

      // Slot B MUST be presented. Before the Part 127 fix `headPreciseReady` required
      // `robIds(head) === io.robHeadIn` (21 vs some other head) and this wait never returns -- the
      // ring is wedged on a dead index and NOTHING can drain again, which is the same
      // permanent-hang class Part 37 fixed one instance of.
      var waited = 0
      while (!dut.io.drain.valid.toBoolean && waited < 200) { cd.waitSampling(); waited += 1 }
      assert(dut.io.drain.valid.toBoolean,
        s"slot B of a flush-kept precise split store was never presented within $waited " +
        "cycles after the ROB head moved on -- the store queue is permanently wedged")
      assert(dut.io.drain.payload.paddr.toLong == 0x2000, "slot B presented next")
      cd.waitSampling()
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
      sleep(1)
      assert(dut.io.empty.toBoolean, "both slots drained -- entry popped cleanly")

      // And the ring is genuinely usable afterwards.
      forkDrainAck(dut, cd)
      alloc(dut, cd, robId = 22, paddr = 0x3000, data = 0x33333333L, Size.LONG)
      commit(dut, cd, robId = 22)
      cd.waitSampling(10)
      sleep(1)
      assert(dut.io.empty.toBoolean, "a new committed store must drain normally afterwards")
      cd.waitSampling(2)
    }
  }

  test("Part 127 (b2): a flush-kept precise entry's completion must be flagged ORPHAN " +
       "so it is never applied to the instruction that inherited its robId", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // Single-slot (non-split) precise entry this time: the mis-completion half of
      // SS8(b) does not need a split, only a kept entry whose robId gets reused.
      alloc(dut, cd, robId = 21, paddr = 0x1000, data = 0x11111111L, Size.LONG,
        vaddr = 0x21000000L, precise = true)
      dut.io.robHeadIn #= 21
      dut.io.robHeadValidIn #= true
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean)
      cd.waitSampling()
      sleep(1)
      assert(dut.drainBusy.toBoolean, "the drain half is accepted and unacked")
      assert(!dut.io.flushKeptPrecise.toBoolean, "not flushing yet")

      dut.io.flush #= true
      cd.waitSampling()
      sleep(1)
      // The SQ must TELL the LS EU it kept this entry -- LsEuPlugin's `pendMem` ring is
      // a lock-step partner of this ring and rolls `pendPush` back to `pendReady` on a
      // flush, which would DISCARD this entry's deferred-write-back record and put the
      // two rings off by one forever (Part 126 SS8(a)).
      assert(dut.io.flushKeptPrecise.toBoolean,
        "the SQ kept an uncommitted precise head across this flush but did not signal it")
      dut.io.flush #= false
      // The ROB re-allocates id 21 to a brand-new instruction.
      dut.io.robHeadIn #= 21
      dut.io.robHeadValidIn #= true
      sleep(1)
      assert(dut.valids(0).toBoolean, "the in-flight entry survives the flush")
      assert(dut.orphans(0).toBoolean, "and is marked ORPHAN -- its ROB entry is gone")

      // Its ack arrives and pops it. `sqCompletion` still fires (the pendMem ring must
      // consume its slot), but it MUST carry the orphan qualifier, or the LS EU will
      // complete -- and write back onto -- the unrelated instruction now holding id 21.
      // `sqCompletion.valid` is COMBINATIONAL and high only during the acking cycle, so
      // it has to be sampled on the edge, not polled after it.
      var sawComp = false; var sawOrphan = false; var compRob = -1
      cd.onSamplings {
        if (dut.io.sqCompletion.valid.toBoolean) {
          sawComp = true
          sawOrphan = dut.io.sqCompletionOrphan.toBoolean
          compRob = dut.io.sqCompletion.payload.toInt
        }
      }
      dut.io.drainAck #= true
      cd.waitSampling()
      sleep(1)
      assert(sawComp, "the pop still announces itself on sqCompletion")
      assert(compRob == 21, s"and it still names the dead store's robId (got $compRob)")
      assert(sawOrphan,
        "a flush-kept entry's completion MUST be flagged orphan -- otherwise robId 21's " +
        "brand-new occupant is marked complete and retires without executing")
      dut.io.drainAck #= false
      cd.waitSampling()
      sleep(1)
      assert(dut.io.empty.toBoolean, "entry popped")

      // A fresh entry must NOT inherit the orphan mark.
      forkDrainAck(dut, cd)
      alloc(dut, cd, robId = 22, paddr = 0x3000, data = 0x33333333L, Size.LONG)
      sleep(1)
      assert(!dut.orphans(1).toBoolean, "a fresh alloc must clear the orphan mark")
      commit(dut, cd, robId = 22)
      cd.waitSampling(10)
      sleep(1)
      assert(dut.io.empty.toBoolean, "the ring is healthy afterwards")
      cd.waitSampling(2)
    }
  }
}
