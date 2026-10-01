package m68k040.mmu

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UmRetireWidthSpec extends AnyFunSuite {
  test("committed U/M bypasses a speculative queue predecessor without changing offered identity", VerilatorTest) {
    M68kSim().withVerilator.compile(new UmWriteQueue(4, retireWidth = 16)).doSim { d =>
      SimTimeout(1000000)
      val cd = d.clockDomain
      cd.forkStimulus(10)
      d.io.alloc.valid #= false; d.io.flush #= false; d.io.drainAck #= false
      d.commitNotices.foreach { c => c.valid #= false; c.payload #= 0 }
      cd.waitSampling(4)
      def alloc(id: Int, addr: Long, byte: Int): Unit = {
        sleep(1); assert(!d.io.full.toBoolean)
        d.io.alloc.valid #= true; d.io.alloc.robId #= (id & 31)
        d.io.alloc.addr #= addr; d.io.alloc.newByte #= byte
        d.io.alloc.preCommitted #= false
        cd.waitSampling(); d.io.alloc.valid #= false; sleep(1)
      }
      def retire(ids: Seq[Int]): Unit = {
        ids.zipWithIndex.foreach { case (id, lane) =>
          d.commitNotices(lane).valid #= true
          d.commitNotices(lane).payload #= (id & 31)
        }
        cd.waitSampling()
        d.commitNotices.foreach(_.valid #= false)
        sleep(1)
      }
      def expectOffer(addr: Long, byte: Int): Unit = {
        assert(d.io.drain.valid.toBoolean,
          f"no committed U/M offer for 0x$addr%08x")
        assert(d.io.drain.addr.toLong == addr && d.io.drain.newByte.toInt == byte,
          f"wrong U/M offer: got 0x${d.io.drain.addr.toLong}%08x/0x${d.io.drain.newByte.toInt}%02x, " +
          f"expected 0x$addr%08x/0x$byte%02x")
      }
      def ack(): Unit = {
        cd.waitSampling(); sleep(1) // first offer edge locks the selected slot
        d.io.drainAck #= true; cd.waitSampling(); d.io.drainAck #= false; sleep(1)
      }

      // Walk completion order is younger then older; the older owner retires
      // first. It must drain without waiting for the speculative younger owner.
      alloc(9, 0x100, 0x19)
      alloc(2, 0x104, 0x19)
      alloc(10, 0x108, 0x19)
      alloc(11, 0x10c, 0x19)
      assert(d.io.full.toBoolean, "the bypass test never saturated the U/M queue")
      retire(Seq(2))
      expectOffer(0x104, 0x19)
      cd.waitSampling(3); sleep(1)
      expectOffer(0x104, 0x19)
      ack()
      assert(!d.io.full.toBoolean, "the interior drain did not free its slot")
      alloc(12, 0x110, 0x19)
      retire(Seq(12))
      expectOffer(0x110, 0x19)
      cd.waitSampling(); sleep(1) // latch the offered slot before flush
      d.io.flush #= true; cd.waitSampling(); d.io.flush #= false; sleep(1)
      expectOffer(0x110, 0x19) // flush kills only speculative entries
      ack()
      assert(!d.io.drain.valid.toBoolean && !d.io.full.toBoolean)

      // Both updates address one byte. Their allocations are younger then
      // older, but dual-retire lane order is older then younger. The drain
      // must preserve the latter order, even across a refused offer.
      alloc(5, 0x200, 0x09)
      alloc(3, 0x200, 0x19)
      retire(Seq(3, 5))
      expectOffer(0x200, 0x19)
      cd.waitSampling(3); sleep(1); expectOffer(0x200, 0x19)
      ack()
      expectOffer(0x200, 0x09)
      ack()
      assert(!d.io.drain.valid.toBoolean && !d.io.full.toBoolean)

      // A precommitted exception/fetch entry allocated on the SAME edge as
      // two retire lanes follows those lanes, irrespective of its free slot.
      alloc(6, 0x300, 0x19)
      alloc(7, 0x304, 0x19)
      d.commitNotices(0).valid #= true; d.commitNotices(0).payload #= 6
      d.commitNotices(1).valid #= true; d.commitNotices(1).payload #= 7
      d.io.alloc.valid #= true; d.io.alloc.robId #= 0
      d.io.alloc.addr #= 0x308; d.io.alloc.newByte #= 0x19
      d.io.alloc.preCommitted #= true
      cd.waitSampling()
      d.commitNotices.foreach(_.valid #= false); d.io.alloc.valid #= false
      sleep(1)
      expectOffer(0x300, 0x19); ack()
      expectOffer(0x304, 0x19); ack()
      expectOffer(0x308, 0x19); ack()
      assert(!d.io.drain.valid.toBoolean)

      // Duplicate notices for one owner (even across all 16 prepared lanes)
      // allocate exactly ONE sequence rank. Hold its offer while three more
      // committed entries arrive, then require drain order to remain intact.
      alloc(21, 0x400, 0x19)
      val seqBefore = d.nextSeq.toInt
      d.commitNotices.foreach { c => c.valid #= true; c.payload #= 21 }
      cd.waitSampling(); d.commitNotices.foreach(_.valid #= false); sleep(1)
      assert(d.nextSeq.toInt == ((seqBefore + 1) & 15),
        "duplicate retirement notices advanced the U/M commit sequence more than once")
      expectOffer(0x400, 0x19)
      for (i <- 0 until 3) {
        alloc(22 + i, 0x404 + 4 * i, 0x19)
        retire(Seq(22 + i))
      }
      assert(d.io.full.toBoolean)
      expectOffer(0x400, 0x19)
      ack()
      for (i <- 0 until 3) { expectOffer(0x404 + 4 * i, 0x19); ack() }

      // Two writes from the same ROB owner share a retirement edge and stamp;
      // physical slot index is their deterministic tie-break.
      alloc(30, 0x500, 0x19); alloc(30, 0x504, 0x19)
      retire(Seq(30))
      expectOffer(0x500, 0x19); ack()
      expectOffer(0x504, 0x19); ack()

      // Wrap the sequence counter repeatedly with no retained entries, then
      // exercise a held oldest offer close to wrap. Unrelated retire notices
      // cannot move nextSeq while that old slot is held.
      for (i <- 0 until 20) {
        alloc(32 + i, 0x600 + 4 * i, 0x19)
        retire(Seq(32 + i))
        expectOffer(0x600 + 4 * i, 0x19); ack()
      }
      alloc(52, 0x700, 0x19); retire(Seq(52))
      expectOffer(0x700, 0x19)
      val heldSeq = d.nextSeq.toInt
      for (i <- 0 until 8) {
        d.commitNotices(0).valid #= true
        d.commitNotices(0).payload #= ((100 + i) & 31)
        cd.waitSampling(); d.commitNotices(0).valid #= false
      }
      sleep(1)
      assert(d.nextSeq.toInt == heldSeq)
      expectOffer(0x700, 0x19); ack()

      // An ack and flush on the same edge consume only the offered entry;
      // a speculative allocation proposed on that edge is discarded.
      alloc(60, 0x800, 0x19); retire(Seq(60))
      expectOffer(0x800, 0x19)
      cd.waitSampling(); sleep(1) // the offer is now held through the collision
      d.io.flush #= true; d.io.drainAck #= true
      d.io.alloc.valid #= true; d.io.alloc.robId #= (61 & 31)
      d.io.alloc.addr #= 0x804; d.io.alloc.newByte #= 0x19
      d.io.alloc.preCommitted #= false
      cd.waitSampling()
      d.io.flush #= false; d.io.drainAck #= false; d.io.alloc.valid #= false
      cd.waitSampling(2); sleep(1)
      assert(!d.io.drain.valid.toBoolean && !d.io.full.toBoolean)
    }
  }

  test("four U/M retirement lanes preserve owned and precommitted entries across flush and reuse", VerilatorTest) {
    M68kSim().withVerilator.compile(new UmWriteQueue(4, retireWidth = 4)).doSim { d =>
      SimTimeout(1000000)
      val cd = d.clockDomain
      cd.forkStimulus(10)
      d.io.alloc.valid #= false; d.io.flush #= false; d.io.drainAck #= false
      d.commitNotices.foreach { c => c.valid #= false; c.payload #= 0 }
      cd.waitSampling(4)
      def alloc(id: Int, address: Long, born: Boolean = false): Unit = {
        sleep(1); assert(!d.io.full.toBoolean)
        d.io.alloc.valid #= true; d.io.alloc.robId #= id; d.io.alloc.addr #= address
        d.io.alloc.newByte #= 0x19; d.io.alloc.preCommitted #= born
        cd.waitSampling(); d.io.alloc.valid #= false; sleep(1)
      }
      def drain(address: Long): Unit = {
        var guard = 0
        while(!d.io.drain.valid.toBoolean && guard < 12) { cd.waitSampling(); sleep(1); guard += 1 }
        assert(d.io.drain.valid.toBoolean && d.io.drain.addr.toLong == address)
        // A refused offer retains its identity/data until acknowledged.
        for(_ <- 0 until 3) {
          cd.waitSampling(); sleep(1)
          assert(d.io.drain.valid.toBoolean && d.io.drain.addr.toLong == address && d.io.drain.newByte.toInt == 0x19)
        }
        d.io.drainAck #= true; cd.waitSampling(); d.io.drainAck #= false; sleep(1)
      }
      def flush(): Unit = {
        d.io.flush #= true; cd.waitSampling(); d.io.flush #= false; sleep(1)
      }
      for(round <- 0 until 32) {
        // Advance the physical ring one slot per round, independently of ROB IDs.
        alloc(7, 0x80, born = true); drain(0x80)
        val base = (round + 30) & 31
        for(i <- 0 until 4) alloc((base + i) & 31, 0x100 + i)
        assert(d.io.full.toBoolean && !d.io.drain.valid.toBoolean)
        for((c, lane) <- d.commitNotices.zipWithIndex) {
          c.valid #= true; c.payload #= ((base + lane) & 31)
        }
        cd.waitSampling(); d.commitNotices.foreach(_.valid #= false)
        flush()
        for(i <- 0 until 4) drain(0x100 + i)
        assert(!d.io.full.toBoolean && !d.io.drain.valid.toBoolean)

        // A dead speculative hole before a later architectural update must not
        // lose the survivor, resurrect the hole, or strand the queue after reuse.
        alloc(4, 0x200); alloc(31, 0x201); alloc(0, 0x202, born = true); alloc(5, 0x203)
        d.io.commitExtra(0).valid #= true; d.io.commitExtra(0).payload #= 31
        cd.waitSampling(); d.io.commitExtra(0).valid #= false
        flush()
        // The born-committed exception/fetch update can now bypass speculative
        // predecessors immediately, before the later lane-2 commit arrives.
        // Its already-offered identity must remain stable through that commit
        // and flush; the newly committed entry follows after its ack.
        drain(0x202); drain(0x201)
        cd.waitSampling(3); sleep(1)
        assert(!d.io.full.toBoolean && !d.io.drain.valid.toBoolean)
      }
      alloc(2, 0x300, born = true)
      cd.assertReset(); sleep(40); cd.deassertReset(); cd.waitSampling(3); sleep(1)
      assert(!d.io.full.toBoolean && !d.io.drain.valid.toBoolean)
    }
  }
}
