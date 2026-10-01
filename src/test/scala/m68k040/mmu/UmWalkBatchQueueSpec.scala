package m68k040.mmu

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UmWalkBatchQueueSpec extends AnyFunSuite {
  test("reserved partial walk bypasses younger batch and drains in descriptor order", VerilatorTest) {
    M68kSim().withVerilator.compile(new UmWalkBatchQueue(4)).doSim { d =>
      SimTimeout(1000000)
      val cd = d.clockDomain
      cd.forkStimulus(10)
      d.io.reserve.valid #= false
      d.io.complete.valid #= false
      d.io.authorize.valid #= false
      d.io.flushOwned #= false
      d.io.cancelOwnerless.valid #= false
      d.io.cancelOwnerless.payload #= 0
      d.io.drainAck #= false
      d.io.drainError #= false
      cd.waitSampling(4)

      def batch(id: Int, ownerless: Boolean, entries: Seq[(Long, Int)]): Unit = {
        sleep(1)
        assert(d.io.reserveReady.toBoolean)
        d.io.reserve.valid #= true
        d.io.reserve.robId #= id
        d.io.reserve.ownerless #= ownerless
        d.io.reserve.epoch #= id
        cd.waitSampling()
        d.io.reserve.valid #= false
        d.io.complete.valid #= true
        for (i <- 0 until 3) {
          d.io.complete.updates(i).valid #= (i < entries.size)
          d.io.complete.updates(i).addr #= (if (i < entries.size) entries(i)._1 else 0L)
          d.io.complete.updates(i).setMask #= (if (i < entries.size) entries(i)._2 else 0)
        }
        cd.waitSampling()
        d.io.complete.valid #= false
        sleep(1)
      }
      def authorize(id: Int): Unit = {
        d.io.authorize.valid #= true
        d.io.authorize.payload #= id
        cd.waitSampling()
        d.io.authorize.valid #= false
        sleep(1)
      }
      def expectOffer(id: Int, addr: Long, level: Int): Unit = {
        assert(d.io.drain.valid.toBoolean, "missing metadata offer")
        assert(d.io.drain.robId.toInt == id)
        assert(d.io.drain.addr.toLong == addr)
        assert(d.io.drain.level.toInt == level)
      }
      def ack(expectDone: Boolean, error: Boolean = false): Unit = {
        cd.waitSampling(); sleep(1) // latch the offered element
        d.io.drainError #= error
        d.io.drainAck #= true
        sleep(1)
        assert(d.io.batchDone.valid.toBoolean == expectDone)
        cd.waitSampling()
        d.io.drainAck #= false
        d.io.drainError #= false
        sleep(1)
      }

      // A younger walk reserves first. An older walk faults at its leaf but
      // keeps the root/pointer U changes, then authorizes at ROB head.
      batch(9, ownerless = false, Seq((0x900L, 8), (0x904L, 8), (0x908L, 24)))
      batch(2, ownerless = false, Seq((0x200L, 8), (0x204L, 8)))
      assert(d.io.freeCount.toInt == 2)
      authorize(2)
      expectOffer(2, 0x200L, 0); ack(expectDone = false)
      expectOffer(2, 0x204L, 1); ack(expectDone = true)
      assert(!d.io.drain.valid.toBoolean, "younger batch drained before authorization")
      authorize(9)
      expectOffer(9, 0x900L, 0); ack(expectDone = false)
      expectOffer(9, 0x904L, 1); ack(expectDone = false)
      expectOffer(9, 0x908L, 2); ack(expectDone = true)
      assert(d.io.freeCount.toInt == 4)

      // A flush poisons an outstanding reservation but cannot release its
      // identity until the old walker produces its late completion.
      d.io.reserve.valid #= true
      d.io.reserve.robId #= 7
      d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.flushOwned #= true
      cd.waitSampling(); d.io.flushOwned #= false
      sleep(1)
      assert(!d.io.reserveReady.toBoolean, "poisoned reservation was reused early")
      d.io.complete.valid #= true
      for (i <- 0 until 3) {
        d.io.complete.updates(i).valid #= (i == 0)
        d.io.complete.updates(i).addr #= 0x700L
        d.io.complete.updates(i).setMask #= 8
      }
      cd.waitSampling(); d.io.complete.valid #= false; sleep(1)
      assert(d.io.freeCount.toInt == 4 && d.io.reserveReady.toBoolean)
      batch(8, ownerless = false, Seq((0x800L, 8)))
      authorize(8)
      expectOffer(8, 0x800L, 0); ack(expectDone = true)

      // Authorization wins a simultaneous backend flush and walker completion.
      d.io.reserve.valid #= true
      d.io.reserve.robId #= 5
      d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x500L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      d.io.authorize.valid #= true; d.io.authorize.payload #= 5
      d.io.flushOwned #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.authorize.valid #= false
      d.io.flushOwned #= false
      sleep(1)
      expectOffer(5, 0x500L, 0); ack(expectDone = true)

      // Failure of the first element aborts other already-authorized batches
      // of that owner; no later metadata write may escape after its fault.
      batch(12, ownerless = false, Seq((0xC00L, 8), (0xC04L, 8)))
      batch(12, ownerless = false, Seq((0xC08L, 8)))
      authorize(12)
      expectOffer(12, 0xC00L, 0); ack(expectDone = true, error = true)
      assert(!d.io.drain.valid.toBoolean && d.io.freeCount.toInt == 4)

      // A split owner's second walk can still be reserved when the head
      // authorization pulse arrives. Completion preserves that authorization.
      batch(13, ownerless = false, Seq((0xD00L, 8)))
      d.io.reserve.valid #= true
      d.io.reserve.robId #= 13
      d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0
      cd.waitSampling(); d.io.reserve.valid #= false
      authorize(13)
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0xD04L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      cd.waitSampling(); d.io.complete.valid #= false; sleep(1)
      expectOffer(13, 0xD00L, 0); ack(expectDone = true)
      expectOffer(13, 0xD04L, 0); ack(expectDone = true)

      // An ownerless fetch epoch may cancel before its first physical offer.
      batch(0, ownerless = true, Seq((0xA00L, 8)))
      d.io.cancelOwnerless.valid #= true
      cd.waitSampling()
      d.io.cancelOwnerless.valid #= false
      sleep(1)
      assert(!d.io.drain.valid.toBoolean && d.io.freeCount.toInt == 4)

      // After the first offer, a late poison cannot withdraw it; it drains.
      batch(0, ownerless = true, Seq((0xA04L, 8), (0xA08L, 8)))
      expectOffer(0, 0xA04L, 0)
      cd.waitSampling(); sleep(1)
      d.io.cancelOwnerless.valid #= true
      cd.waitSampling()
      d.io.cancelOwnerless.valid #= false
      expectOffer(0, 0xA04L, 0)
      ack(expectDone = false)
      d.io.cancelOwnerless.valid #= true
      cd.waitSampling()
      d.io.cancelOwnerless.valid #= false
      sleep(1)
      expectOffer(0, 0xA08L, 1)
      ack(expectDone = true)
    }
  }
}
