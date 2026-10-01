package m68k040.mmu

import spinal.core._
import spinal.lib._

/** One reserved walker result occupies one slot regardless of how many of its
  * three descriptor levels need an update. */
case class UmWalkReservation() extends Bundle {
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val ownerless = Bool()
  val epoch = UInt(8 bits) // frontend/exception identity for ownerless split batches
}

case class UmWalkBatchDrain() extends Bundle {
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val ownerless = Bool()
  val epoch = UInt(8 bits)
  val addr = UInt(32 bits)
  val setMask = Bits(8 bits)
  val level = UInt(2 bits)
}

case class UmWalkBatchDone() extends Bundle {
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val ownerless = Bool()
  val epoch = UInt(8 bits)
  val error = Bool()
}

/** Storage and offer protocol for ordered root/pointer/page U/M updates.
  *
  * One walker may hold a reservation at a time. The caller applies the
  * two-free-slot ROB-head rule before asserting reserve; freeCount includes
  * the in-flight reservation. A clean result with no changed bytes frees its
  * reservation. The ROB authorizes owned batches at head; an ownerless batch
  * is eligible after completion but can be canceled before its first offer.
  * Offered elements stay stable through their terminal ack, even on flush.
  * A canceled in-flight reservation remains owned until the walker completes,
  * so a late result cannot attach to a replacement walk. A drain error aborts
  * all sibling batches with the same owner; `batchDone.error` is the terminal
  * fault report. Cross-plugin SQ ordering and aggregation of successful
  * split-batch completions are separate stages.
  */
class UmWalkBatchQueue(depth: Int = 4) extends Component {
  require(depth >= 2 && isPow2(depth))
  private val slotW = log2Up(depth)
  val io = new Bundle {
    val reserve = slave(Flow(UmWalkReservation()))
    val reserveReady = out Bool()
    val freeCount = out UInt(log2Up(depth + 1) bits)
    val complete = slave(Flow(WalkUmBatch()))
    val authorize = slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
    val flushOwned = in Bool()
    val cancelOwnerless = slave(Flow(UInt(8 bits)))
    val drain = master(Flow(UmWalkBatchDrain()))
    val drainAck = in Bool()
    val drainError = in Bool()
    val batchDone = master(Flow(UmWalkBatchDone()))
  }

  val occupied = Vec.fill(depth)(RegInit(False))
  val reserved = Vec.fill(depth)(RegInit(False))
  val authorized = Vec.fill(depth)(RegInit(False))
  val ownerless = Vec.fill(depth)(RegInit(False))
  val epoch = Vec.fill(depth)(Reg(UInt(8 bits)) init 0)
  val everOffered = Vec.fill(depth)(RegInit(False))
  val robId = Vec.fill(depth)(Reg(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)) init 0)
  val elementValid = Vec.fill(depth)(Vec.fill(3)(RegInit(False)))
  val elementAddr = Vec.fill(depth)(Vec.fill(3)(Reg(UInt(32 bits)) init 0))
  val elementMask = Vec.fill(depth)(Vec.fill(3)(Reg(Bits(8 bits)) init 0))
  // Relative allocation order has no wrapping counter: a newly reserved slot
  // is younger than each live slot. Reusing a freed slot rewrites its row and
  // column, so arbitrary intervening reservations cannot reverse survivors.
  val older = Vec.fill(depth)(Vec.fill(depth)(RegInit(False)))

  val reservationLive = RegInit(False)
  val reservationPoisoned = RegInit(False)
  val reservationSlot = Reg(UInt(slotW bits)) init 0
  io.freeCount := CountOne(~occupied.asBits).resize(io.freeCount.getWidth)
  io.reserveReady := !reservationLive && (io.freeCount =/= 0) &&
                     !io.flushOwned && !io.cancelOwnerless.valid
  val freeSlot = OHToUInt(OHMasking.first(~occupied.asBits))

  when(io.reserve.valid) {
    assert(io.reserveReady, "UmWalkBatchQueue: reservation without credit")
    when(io.reserveReady) {
      occupied(freeSlot) := True
      reserved(freeSlot) := True
      authorized(freeSlot) := False
      ownerless(freeSlot) := io.reserve.payload.ownerless
      epoch(freeSlot) := io.reserve.payload.epoch
      everOffered(freeSlot) := False
      robId(freeSlot) := io.reserve.payload.robId
      for (k <- 0 until 3) elementValid(freeSlot)(k) := False
      for (j <- 0 until depth) {
        older(j)(freeSlot) := occupied(j)
        older(freeSlot)(j) := False
      }
      reservationLive := True
      reservationPoisoned := False
      reservationSlot := freeSlot
    }
  }

  when(io.complete.valid) {
    assert(reservationLive, "UmWalkBatchQueue: completion without reservation")
    when(reservationLive) {
      val any = io.complete.payload.updates.map(_.valid).reduce(_ || _)
      reserved(reservationSlot) := False
      reservationLive := False
      reservationPoisoned := False
      val authNow = io.authorize.valid && !ownerless(reservationSlot) &&
                    (robId(reservationSlot) === io.authorize.payload)
      val poisonedNow = reservationPoisoned ||
        (io.flushOwned && !ownerless(reservationSlot) && !authNow) ||
        (io.cancelOwnerless.valid && ownerless(reservationSlot) &&
         (epoch(reservationSlot) === io.cancelOwnerless.payload))
      when(!any || poisonedNow) {
        occupied(reservationSlot) := False
      } otherwise {
        authorized(reservationSlot) := authorized(reservationSlot) || ownerless(reservationSlot)
        for (k <- 0 until 3) {
          elementValid(reservationSlot)(k) := io.complete.payload.updates(k).valid
          elementAddr(reservationSlot)(k) := io.complete.payload.updates(k).addr
          elementMask(reservationSlot)(k) := io.complete.payload.updates(k).setMask
          when(io.complete.payload.updates(k).valid) {
            assert((io.complete.payload.updates(k).setMask & ~B"00011000") === B(0, 8 bits),
              "UmWalkBatchQueue: non-U/M bit in set mask")
          }
        }
      }
    }
  }

  when(io.authorize.valid) {
    for (i <- 0 until depth) {
      when(occupied(i) && !ownerless(i) &&
           (robId(i) === io.authorize.payload)) {
        authorized(i) := True
      }
    }
  }

  // Choose the oldest eligible batch, then its earliest undrained element.
  val eligible = Vec.fill(depth)(Bool())
  for (i <- 0 until depth) eligible(i) := occupied(i) && !reserved(i) && authorized(i)
  var bestValid: Bool = False
  var bestSlot: UInt = U(0, slotW bits)
  for (i <- 0 until depth) {
    val olderEligible = (0 until depth).map(j => eligible(j) && older(j)(i)).reduce(_ || _)
    val choose = eligible(i) && !olderEligible
    bestSlot = Mux(choose, U(i, slotW bits), bestSlot)
    bestValid = bestValid || choose
  }
  val offerBusy = RegInit(False)
  val offerSlot = Reg(UInt(slotW bits)) init 0
  val offerLevel = Reg(UInt(2 bits)) init 0
  val selectedSlot = Mux(offerBusy, offerSlot, bestSlot)
  val selectedLevel = Mux(offerBusy, offerLevel,
    Mux(elementValid(bestSlot)(0), U(0, 2 bits),
      Mux(elementValid(bestSlot)(1), U(1, 2 bits), U(2, 2 bits))))
  val cancelSelected = !offerBusy && bestValid && ownerless(bestSlot) &&
                       !everOffered(bestSlot) && io.cancelOwnerless.valid &&
                       (epoch(bestSlot) === io.cancelOwnerless.payload)
  io.drain.valid := (offerBusy || bestValid) && !cancelSelected
  io.drain.payload.robId := robId(selectedSlot)
  io.drain.payload.ownerless := ownerless(selectedSlot)
  io.drain.payload.epoch := epoch(selectedSlot)
  io.drain.payload.addr := elementAddr(selectedSlot)(selectedLevel)
  io.drain.payload.setMask := elementMask(selectedSlot)(selectedLevel)
  io.drain.payload.level := selectedLevel
  when(io.drain.valid && !offerBusy) {
    offerBusy := True
    offerSlot := selectedSlot
    offerLevel := selectedLevel
    everOffered(selectedSlot) := True
  }

  io.batchDone.valid := False
  io.batchDone.payload.robId := robId(offerSlot)
  io.batchDone.payload.ownerless := ownerless(offerSlot)
  io.batchDone.payload.epoch := epoch(offerSlot)
  io.batchDone.payload.error := io.drainError
  when(io.drainAck) {
    assert(offerBusy, "UmWalkBatchQueue: ack without held offer")
    when(offerBusy) {
      offerBusy := False
      elementValid(offerSlot)(offerLevel) := False
      val more = (0 until 3).map(k =>
        elementValid(offerSlot)(k) && (offerLevel =/= U(k, 2 bits))).reduce(_ || _)
      when(io.drainError || !more) {
        occupied(offerSlot) := False
        io.batchDone.valid := True
      }
      when(io.drainError) {
        // An earlier metadata bus error terminates the WHOLE owner batch set;
        // no later root/pointer/page update for that owner may hit memory.
        for (i <- 0 until depth) {
          val sameOwner = (ownerless(i) === ownerless(offerSlot)) &&
            Mux(ownerless(i), epoch(i) === epoch(offerSlot),
                robId(i) === robId(offerSlot))
          when(occupied(i) && sameOwner) {
            when(reserved(i)) { reservationPoisoned := True }
            .otherwise { occupied(i) := False }
          }
        }
      }
    }
  }

  when(io.flushOwned || io.cancelOwnerless.valid) {
    for (i <- 0 until depth) {
      val authNow = io.authorize.valid && !ownerless(i) &&
                    (robId(i) === io.authorize.payload)
      val dropOwned = io.flushOwned && !ownerless(i) &&
                      !authorized(i) && !authNow
      val dropOwnerless = io.cancelOwnerless.valid && ownerless(i) &&
                          (epoch(i) === io.cancelOwnerless.payload) &&
                          !everOffered(i) &&
                          !(offerBusy && (offerSlot === U(i, slotW bits)))
      when(occupied(i) && (dropOwned || dropOwnerless)) {
        when(reserved(i)) {
          // Keep the reservation identity until its late walker completion.
          // The single walker cannot attach that completion to a new owner.
          reservationPoisoned := True
        } otherwise {
          occupied(i) := False
        }
      }
    }
  }

  GenerationFlags.simulation {
    when(offerBusy) {
      assert(occupied(offerSlot) && authorized(offerSlot) &&
             elementValid(offerSlot)(offerLevel),
        "UmWalkBatchQueue: held offer lost its batch/element")
    }
    assert(!(io.complete.valid && io.reserve.valid),
      "UmWalkBatchQueue: single walker cannot complete and reserve together")
  }
}
