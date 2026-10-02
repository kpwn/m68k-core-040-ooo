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
      d.io.beginOwnerless.valid #= false
      d.io.beginOwnerless.epoch #= 0
      d.io.beginOwnerless.walks #= 0
      d.io.complete.valid #= false
      d.io.authorize.valid #= false
      d.io.sealOwnerless.valid #= false
      d.io.sealOwnerless.payload #= 0
      d.io.flushOwned #= false
      d.io.cancelOwnerless.valid #= false
      d.io.cancelOwnerless.payload #= 0
      d.io.drainAck #= false
      d.io.drainError #= false
      cd.waitSampling(4)

      def batch(id: Int, ownerless: Boolean, entries: Seq[(Long, Int)]): Unit = {
        sleep(1)
        if (ownerless) {
          d.io.beginOwnerless.epoch #= id
          d.io.beginOwnerless.walks #= 1
          sleep(1)
          assert(d.io.beginReady.toBoolean)
          d.io.beginOwnerless.valid #= true
          cd.waitSampling(); d.io.beginOwnerless.valid #= false
          sleep(1)
        }
        d.io.reserve.robId #= id
        d.io.reserve.ownerless #= ownerless
        d.io.reserve.epoch #= id
        sleep(1)
        assert(d.io.reserveReady.toBoolean)
        d.io.reserve.valid #= true
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
        if (ownerless) {
          d.io.sealOwnerless.payload #= id
          d.io.sealOwnerless.valid #= true
          cd.waitSampling(); d.io.sealOwnerless.valid #= false
        }
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

      // A previously authorized second batch remains architectural when its
      // completion coincides with a later backend flush.
      batch(14, ownerless = false, Seq((0xE00L, 8)))
      d.io.reserve.robId #= 14; d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      authorize(14)
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0xE04L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      d.io.flushOwned #= true
      cd.waitSampling(); d.io.complete.valid #= false; d.io.flushOwned #= false
      sleep(1)
      expectOffer(14, 0xE00L, 0); ack(expectDone = true)
      expectOffer(14, 0xE04L, 0); ack(expectDone = true)

      // If an offered batch faults on the SAME edge its reserved sibling
      // completes, the sibling must never become eligible next cycle.
      batch(16, ownerless = false, Seq((0x1000L, 8)))
      d.io.reserve.robId #= 16; d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      authorize(16)
      expectOffer(16, 0x1000L, 0)
      cd.waitSampling(); sleep(1) // first offer is now held
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x1004L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      d.io.drainAck #= true; d.io.drainError #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.drainAck #= false
      d.io.drainError #= false
      sleep(1)
      assert(!d.io.drain.valid.toBoolean && d.io.freeCount.toInt == 4)

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

      // Seal, not first completion, releases a two-walk ownerless epoch. Two
      // speculative owned batches leave exactly two physical slots, both
      // reserved for this epoch; another owned walk cannot steal the second.
      batch(30, ownerless = false, Seq((0x3000L, 8)))
      batch(31, ownerless = false, Seq((0x3100L, 8)))
      d.io.beginOwnerless.epoch #= 21
      d.io.beginOwnerless.walks #= 2
      sleep(1); assert(d.io.beginReady.toBoolean)
      d.io.beginOwnerless.valid #= true
      cd.waitSampling(); d.io.beginOwnerless.valid #= false
      d.io.reserve.ownerless #= false
      sleep(1); assert(!d.io.reserveReady.toBoolean,
        "owned walk stole a held ownerless split credit")
      def epochWalk(addr: Long): Unit = {
        d.io.reserve.robId #= 0
        d.io.reserve.ownerless #= true
        d.io.reserve.epoch #= 21
        sleep(1); assert(d.io.reserveReady.toBoolean)
        d.io.reserve.valid #= true
        cd.waitSampling(); d.io.reserve.valid #= false
        d.io.complete.valid #= true
        d.io.complete.updates(0).valid #= true
        d.io.complete.updates(0).addr #= addr
        d.io.complete.updates(0).setMask #= 8
        for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
        cd.waitSampling(); d.io.complete.valid #= false; sleep(1)
      }
      epochWalk(0x2100L)
      assert(!d.io.drain.valid.toBoolean, "unsealed ownerless batch escaped")
      epochWalk(0x2104L)
      d.io.sealOwnerless.payload #= 21
      d.io.sealOwnerless.valid #= true
      cd.waitSampling(); d.io.sealOwnerless.valid #= false; sleep(1)
      expectOffer(0, 0x2100L, 0)
      cd.waitSampling(); sleep(1) // first offer makes BOTH siblings irrevocable
      d.io.cancelOwnerless.payload #= 21
      d.io.cancelOwnerless.valid #= true
      cd.waitSampling(); d.io.cancelOwnerless.valid #= false; sleep(1)
      expectOffer(0, 0x2100L, 0); ack(expectDone = true)
      expectOffer(0, 0x2104L, 0); ack(expectDone = true)
      d.io.flushOwned #= true
      cd.waitSampling(); d.io.flushOwned #= false; sleep(1)
      assert(d.io.freeCount.toInt == 4)

      // No-update completion and seal on the same edge leave no stale epoch.
      d.io.beginOwnerless.epoch #= 22; d.io.beginOwnerless.walks #= 1
      d.io.beginOwnerless.valid #= true
      cd.waitSampling(); d.io.beginOwnerless.valid #= false
      d.io.reserve.robId #= 0; d.io.reserve.ownerless #= true
      d.io.reserve.epoch #= 22; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.complete.valid #= true
      for (i <- 0 until 3) d.io.complete.updates(i).valid #= false
      d.io.sealOwnerless.payload #= 22; d.io.sealOwnerless.valid #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.sealOwnerless.valid #= false
      sleep(1)
      assert(d.io.freeCount.toInt == 4 && !d.io.drain.valid.toBoolean)

      // A terminal fault on the first walk seals early: the partial root U
      // still drains, and the second held credit is returned without a walk.
      d.io.beginOwnerless.epoch #= 24; d.io.beginOwnerless.walks #= 2
      d.io.beginOwnerless.valid #= true
      cd.waitSampling(); d.io.beginOwnerless.valid #= false
      d.io.reserve.robId #= 0; d.io.reserve.ownerless #= true
      d.io.reserve.epoch #= 24; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x2400L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      d.io.sealOwnerless.payload #= 24; d.io.sealOwnerless.valid #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.sealOwnerless.valid #= false
      sleep(1)
      d.io.reserve.ownerless #= false
      sleep(1)
      assert(d.io.reserveReady.toBoolean,
        "terminal fault did not release unused split credit")
      expectOffer(0, 0x2400L, 0); ack(expectDone = true)
      assert(d.io.freeCount.toInt == 4)

      // After seal, the first offered sibling makes a still-reserved second
      // walk irrevocable. Its completion plus late cancel must be kept.
      d.io.beginOwnerless.epoch #= 25; d.io.beginOwnerless.walks #= 2
      d.io.beginOwnerless.valid #= true
      cd.waitSampling(); d.io.beginOwnerless.valid #= false
      d.io.reserve.robId #= 0; d.io.reserve.ownerless #= true
      d.io.reserve.epoch #= 25; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x2500L
      d.io.complete.updates(0).setMask #= 8
      cd.waitSampling(); d.io.complete.valid #= false
      d.io.reserve.valid #= true // capture second walk; leave it unresolved
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.sealOwnerless.payload #= 25; d.io.sealOwnerless.valid #= true
      cd.waitSampling(); d.io.sealOwnerless.valid #= false
      sleep(1)
      expectOffer(0, 0x2500L, 0)
      cd.waitSampling(); sleep(1) // epoch-wide irrevocable offer edge
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x2504L
      d.io.complete.updates(0).setMask #= 8
      d.io.cancelOwnerless.payload #= 25; d.io.cancelOwnerless.valid #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.cancelOwnerless.valid #= false
      sleep(1)
      expectOffer(0, 0x2500L, 0); ack(expectDone = true)
      expectOffer(0, 0x2504L, 0); ack(expectDone = true)
      assert(d.io.freeCount.toInt == 4)

      // A simultaneous pre-offer poison wins completion plus seal.
      d.io.beginOwnerless.epoch #= 23; d.io.beginOwnerless.walks #= 1
      d.io.beginOwnerless.valid #= true
      cd.waitSampling(); d.io.beginOwnerless.valid #= false
      d.io.reserve.epoch #= 23; d.io.reserve.valid #= true
      cd.waitSampling(); d.io.reserve.valid #= false
      d.io.complete.valid #= true
      d.io.complete.updates(0).valid #= true
      d.io.complete.updates(0).addr #= 0x2300L
      d.io.complete.updates(0).setMask #= 8
      for (i <- 1 until 3) d.io.complete.updates(i).valid #= false
      d.io.sealOwnerless.payload #= 23; d.io.sealOwnerless.valid #= true
      d.io.cancelOwnerless.payload #= 23; d.io.cancelOwnerless.valid #= true
      cd.waitSampling()
      d.io.complete.valid #= false; d.io.sealOwnerless.valid #= false
      d.io.cancelOwnerless.valid #= false
      sleep(1)
      assert(d.io.freeCount.toInt == 4 && !d.io.drain.valid.toBoolean)
    }
  }

  test("metadata control event-pair matrix preserves offer, cancellation and credit contracts", VerilatorTest) {
    val pairs = Seq(
      "reserve+flush", "reserve+cancel", "reserve+authorize", "complete+authorize",
      "complete+flush", "complete+seal", "complete+cancel",
      "authorize+flush", "seal+cancel", "ack+flush", "ack+cancel",
      "seal+flush", "reserve+ack", "ackerror+flush", "ackerror+cancel",
      "reserve+ackerror")
    val compiled = M68kSim().withVerilator.compile(new UmWalkBatchQueue(4))
    for (pair <- pairs) compiled.doSim(pair) { d =>
      SimTimeout(1000000)
      val cd = d.clockDomain
      cd.forkStimulus(10)
      d.io.reserve.valid #= false
      d.io.reserve.robId #= 0
      d.io.reserve.ownerless #= false
      d.io.reserve.epoch #= 0
      d.io.beginOwnerless.valid #= false
      d.io.beginOwnerless.epoch #= 0
      d.io.beginOwnerless.walks #= 1
      d.io.complete.valid #= false
      for (i <- 0 until 3) {
        d.io.complete.updates(i).valid #= false
        d.io.complete.updates(i).addr #= 0
        d.io.complete.updates(i).setMask #= 0
      }
      d.io.authorize.valid #= false
      d.io.authorize.payload #= 0
      d.io.sealOwnerless.valid #= false
      d.io.sealOwnerless.payload #= 0
      d.io.flushOwned #= false
      d.io.cancelOwnerless.valid #= false
      d.io.cancelOwnerless.payload #= 0
      d.io.drainAck #= false
      d.io.drainError #= false
      cd.waitSampling(4)
      def tick(): Unit = { cd.waitSampling(); sleep(1) }
      def reserveOwned(id: Int): Unit = {
        d.io.reserve.robId #= id; d.io.reserve.ownerless #= false
        d.io.reserve.valid #= true; tick(); d.io.reserve.valid #= false
      }
      def begin(epoch: Int): Unit = {
        d.io.beginOwnerless.epoch #= epoch
        d.io.beginOwnerless.walks #= 1
        d.io.beginOwnerless.valid #= true; tick()
        d.io.beginOwnerless.valid #= false
      }
      def reserveOwnerless(epoch: Int): Unit = {
        d.io.reserve.robId #= 0; d.io.reserve.ownerless #= true
        d.io.reserve.epoch #= epoch; d.io.reserve.valid #= true
        tick(); d.io.reserve.valid #= false
      }
      def completeOne(addr: Long): Unit = {
        d.io.complete.updates(0).valid #= true
        d.io.complete.updates(0).addr #= addr
        d.io.complete.updates(0).setMask #= 8
        d.io.complete.valid #= true; tick(); d.io.complete.valid #= false
      }
      def authorize(id: Int): Unit = {
        d.io.authorize.payload #= id; d.io.authorize.valid #= true
        tick(); d.io.authorize.valid #= false
      }
      def seal(epoch: Int): Unit = {
        d.io.sealOwnerless.payload #= epoch; d.io.sealOwnerless.valid #= true
        tick(); d.io.sealOwnerless.valid #= false
      }
      def offer(addr: Long): Unit = {
        assert(d.io.drain.valid.toBoolean, s"$pair lost an irrevocable offer")
        assert(d.io.drain.addr.toLong == addr, s"$pair offered wrong address")
        assert(d.io.drain.setMask.toInt == 8, s"$pair changed offered mask")
      }
      def ack(): Unit = {
        tick() // hold one full cycle to catch unstable payloads
        d.io.drainAck #= true; tick(); d.io.drainAck #= false
      }
      val addr = 0x4000L
      pair match {
        case "reserve+flush" =>
          d.io.flushOwned #= true; sleep(1)
          assert(!d.io.reserveReady.toBoolean)
          tick(); d.io.flushOwned #= false
        case "reserve+cancel" =>
          d.io.cancelOwnerless.valid #= true; sleep(1)
          assert(!d.io.reserveReady.toBoolean)
          tick(); d.io.cancelOwnerless.valid #= false
        case "reserve+authorize" =>
          d.io.reserve.robId #= 1; d.io.reserve.ownerless #= false
          d.io.reserve.valid #= true
          d.io.authorize.payload #= 1; d.io.authorize.valid #= true
          tick(); d.io.reserve.valid #= false; d.io.authorize.valid #= false
          completeOne(addr)
          offer(addr); ack()
        case "complete+authorize" =>
          reserveOwned(1)
          d.io.complete.updates(0).valid #= true
          d.io.complete.updates(0).addr #= addr
          d.io.complete.updates(0).setMask #= 8
          d.io.complete.valid #= true
          d.io.authorize.payload #= 1; d.io.authorize.valid #= true
          tick(); d.io.complete.valid #= false; d.io.authorize.valid #= false
          offer(addr); ack()
        case "complete+flush" =>
          reserveOwned(1)
          d.io.complete.updates(0).valid #= true
          d.io.complete.updates(0).addr #= addr
          d.io.complete.updates(0).setMask #= 8
          d.io.complete.valid #= true; d.io.flushOwned #= true
          tick(); d.io.complete.valid #= false; d.io.flushOwned #= false
        case "complete+seal" =>
          begin(2); reserveOwnerless(2)
          d.io.complete.updates(0).valid #= true
          d.io.complete.updates(0).addr #= addr
          d.io.complete.updates(0).setMask #= 8
          d.io.complete.valid #= true
          d.io.sealOwnerless.payload #= 2; d.io.sealOwnerless.valid #= true
          tick(); d.io.complete.valid #= false; d.io.sealOwnerless.valid #= false
          offer(addr); ack()
        case "complete+cancel" =>
          begin(2); reserveOwnerless(2)
          d.io.complete.updates(0).valid #= true
          d.io.complete.updates(0).addr #= addr
          d.io.complete.updates(0).setMask #= 8
          d.io.complete.valid #= true
          d.io.cancelOwnerless.payload #= 2; d.io.cancelOwnerless.valid #= true
          tick(); d.io.complete.valid #= false; d.io.cancelOwnerless.valid #= false
        case "authorize+flush" =>
          reserveOwned(1); completeOne(addr)
          d.io.authorize.payload #= 1; d.io.authorize.valid #= true
          d.io.flushOwned #= true
          tick(); d.io.authorize.valid #= false; d.io.flushOwned #= false
          offer(addr); ack()
        case "seal+cancel" =>
          begin(2); reserveOwnerless(2); completeOne(addr)
          d.io.sealOwnerless.payload #= 2; d.io.sealOwnerless.valid #= true
          d.io.cancelOwnerless.payload #= 2; d.io.cancelOwnerless.valid #= true
          tick(); d.io.sealOwnerless.valid #= false
          d.io.cancelOwnerless.valid #= false
        case "ack+flush" =>
          reserveOwned(1); completeOne(addr); authorize(1); offer(addr)
          tick(); offer(addr)
          d.io.drainAck #= true; d.io.flushOwned #= true
          tick(); d.io.drainAck #= false; d.io.flushOwned #= false
        case "ack+cancel" =>
          begin(2); reserveOwnerless(2); completeOne(addr); seal(2)
          offer(addr); tick(); offer(addr)
          d.io.drainAck #= true
          d.io.cancelOwnerless.payload #= 2; d.io.cancelOwnerless.valid #= true
          tick(); d.io.drainAck #= false; d.io.cancelOwnerless.valid #= false
        case "seal+flush" =>
          begin(2); reserveOwnerless(2); completeOne(addr)
          d.io.sealOwnerless.payload #= 2; d.io.sealOwnerless.valid #= true
          d.io.flushOwned #= true
          tick(); d.io.sealOwnerless.valid #= false; d.io.flushOwned #= false
          offer(addr); ack()
        case "reserve+ack" =>
          reserveOwned(1); completeOne(addr); authorize(1)
          offer(addr); tick(); offer(addr)
          d.io.reserve.robId #= 3; d.io.reserve.ownerless #= false
          d.io.reserve.valid #= true; d.io.drainAck #= true
          tick(); d.io.reserve.valid #= false; d.io.drainAck #= false
          completeOne(0x4004L); authorize(3)
          offer(0x4004L); ack()
        case "ackerror+flush" =>
          reserveOwned(1); completeOne(addr); authorize(1)
          offer(addr); tick(); offer(addr)
          d.io.drainAck #= true; d.io.drainError #= true
          d.io.flushOwned #= true
          tick(); d.io.drainAck #= false; d.io.drainError #= false
          d.io.flushOwned #= false
        case "ackerror+cancel" =>
          begin(2); reserveOwnerless(2); completeOne(addr); seal(2)
          offer(addr); tick(); offer(addr)
          d.io.drainAck #= true; d.io.drainError #= true
          d.io.cancelOwnerless.payload #= 2; d.io.cancelOwnerless.valid #= true
          tick(); d.io.drainAck #= false; d.io.drainError #= false
          d.io.cancelOwnerless.valid #= false
        case "reserve+ackerror" =>
          reserveOwned(1); completeOne(addr); authorize(1)
          offer(addr); tick(); offer(addr)
          d.io.reserve.robId #= 1; d.io.reserve.ownerless #= false
          d.io.drainAck #= true; d.io.drainError #= true
          sleep(1)
          assert(!d.io.reserveReady.toBoolean,
            "erroring owner accepted a new same-cycle reservation")
          tick(); d.io.drainAck #= false; d.io.drainError #= false
      }
      tick()
      assert(!d.io.drain.valid.toBoolean, s"$pair leaked a canceled/completed offer")
      assert(d.io.freeCount.toInt == 4, s"$pair leaked a batch credit")
    }
  }
}
