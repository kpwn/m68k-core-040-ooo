package m68k040.ls

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class MemoryDependencyTrackerSpec extends AnyFunSuite {
  for (entries <- Seq(2, 4, 8)) test(s"fixed-seed lifecycle model checks concurrent notices and stale tickets; entries=$entries") {
    M68kSim().compile(new MemoryDependencyTracker(entries)).doSim { d =>
      SimTimeout(1000000)
      // Each transition queries every live slot plus two stale identities with
      // one-nanosecond settling. Keep the entire sweep and next input setup
      // before the next active edge, including the eight-entry configuration.
      val cd = d.clockDomain; cd.forkStimulus(100)
      val rng = new scala.util.Random(0x68040dL)
      val robMask = (1 << m68k040.Global.ROB_ID_W_DEFAULT) - 1
      case class Ticket(slot: Int, generation: BigInt)
      case class Record(ticket: Ticket, age: Long, store: Boolean,
                        serial: Boolean, known: Boolean = false, data: Boolean = false,
                        committed: Boolean = false, irreversible: Boolean = false,
                        canceled: Boolean = false, bytes: Set[Int] = Set.empty)
      val records = Array.fill[Option[Record]](entries)(None)
      val generations = Array.fill[BigInt](entries)(0)
      val history = scala.collection.mutable.ArrayBuffer.empty[Ticket]
      var nextAge = 0L
      def drive(p: MemoryOrderTicket, t: Ticket): Unit = {
        p.slot #= t.slot; p.generation #= t.generation
      }
      def clear(): Unit = {
        d.io.reserve.valid #= false; d.io.reserveSecond #= false
        d.io.address.valid #= false; d.io.dataReady.valid #= false
        d.io.commit.foreach(_.valid #= false); d.io.release.foreach(_.valid #= false)
        d.io.irreversible.valid #= false; d.io.flush #= false
        d.io.query.valid #= false; d.io.atCommit #= false
      }
      def ticket(): Ticket = if (history.nonEmpty && rng.nextInt(4) != 0)
        history(rng.nextInt(history.size)) else Ticket(rng.nextInt(entries), BigInt(rng.nextInt(20)))
      def notice(p: spinal.lib.Flow[MemoryOrderTicket]): Option[Ticket] = {
        val t = if(rng.nextBoolean()) Some(ticket()) else None
        p.valid #= t.nonEmpty; t.foreach(drive(p.payload, _)); t
      }
      clear(); cd.waitSampling(5); sleep(1)
      for (cycle <- 0 until 2048) {
        clear()
        val flush = rng.nextInt(13) == 0; d.io.flush #= flush
        val commits = d.io.commit.map(notice).toSeq.flatten
        val releases = d.io.release.map(notice).toSeq.flatten
        val irreversible = notice(d.io.irreversible)
        val data = notice(d.io.dataReady)
        val address = if(rng.nextBoolean()) Some(ticket()) else None
        val addressSerial = rng.nextInt(7) == 0
        val pieces = Seq.fill(2)((rng.nextInt(8), rng.nextInt(65536)))
        val bytes = pieces.flatMap { case (line, mask) =>
          (0 until 16).filter(b => (mask & (1 << b)) != 0).map(line * 16 + _)
        }.toSet
        d.io.address.valid #= address.nonEmpty; d.io.address.serial #= addressSerial
        address.foreach(drive(d.io.address.ticket, _))
        for (n <- 0 until 2) {
          d.io.address.fragments(n).line #= pieces(n)._1
          d.io.address.fragments(n).mask #= pieces(n)._2
        }
        val reserve = rng.nextBoolean(); val two = rng.nextBoolean()
        // Deliberately arbitrary/reused ROB IDs: order must come from allocation,
        // never numeric ID order or a sampled live ROB head.
        val allocations = Seq.fill(2)((rng.nextInt(robMask + 1), rng.nextBoolean(), rng.nextInt(7) == 0))
        d.io.reserve.valid #= reserve; d.io.reserveSecond #= two
        for (n <- 0 until 2) {
          d.io.reserve.payload(n).robId #= allocations(n)._1
          d.io.reserve.payload(n).store #= allocations(n)._2
          d.io.reserve.payload(n).serial #= allocations(n)._3
        }
        val free = records.indices.filter(records(_).isEmpty)
        val ready = !flush && free.size >= (if(two) 2 else 1)
        sleep(1)
        assert(d.io.reserve.ready.toBoolean == ready, s"reservation cycle=$cycle")
        if(reserve && ready) for(n <- 0 until (if(two) 2 else 1)) {
          assert(d.io.tickets(n).slot.toInt == free(n))
          assert(d.io.tickets(n).generation.toBigInt == generations(free(n)) + 1)
        }
        for(n <- records.indices) records(n).foreach { old =>
          var next = old
          if(address.contains(old.ticket) && !old.canceled)
            next = next.copy(known = true, serial = old.serial || addressSerial, bytes = bytes)
          if(data.contains(old.ticket) && !old.canceled) next = next.copy(data = true)
          val committing = commits.contains(old.ticket)
          val launching = irreversible.contains(old.ticket)
          if(committing && !old.canceled) next = next.copy(committed = true)
          if(launching && !old.canceled) next = next.copy(irreversible = true)
          if(flush && !old.committed && !old.irreversible && !committing && !launching)
            next = next.copy(canceled = true)
          records(n) = if(releases.contains(old.ticket)) None else Some(next)
        }
        if(reserve && ready) for(n <- 0 until (if(two) 2 else 1)) {
          val slot = free(n); generations(slot) += 1
          val t = Ticket(slot, generations(slot)); history += t
          val (_, store, serial) = allocations(n)
          records(slot) = Some(Record(t, nextAge, store, serial))
          nextAge += 1
        }
        cd.waitSampling(); sleep(1); clear()
        // Query several current and stale identities after each state transition.
        for(t <- Seq(ticket(), ticket()) ++ records.toSeq.flatten.map(_.ticket)) {
          val atCommit = rng.nextBoolean()
          d.io.atCommit #= atCommit; drive(d.io.query.payload, t); d.io.query.valid #= true
          sleep(1)
          val q = records(t.slot).filter(r => r.ticket == t && !r.canceled)
          assert(d.io.queryPresent.toBoolean == q.nonEmpty, s"presence cycle=$cycle ticket=$t")
          var unknown = 0; var barrier = 0; var overlap = 0; var waiting = 0
          q.filterNot(_.store).foreach { query =>
            records.toSeq.flatten.filter(r => !r.canceled && r.ticket != query.ticket &&
              (r.committed || r.irreversible || r.age < query.age)).foreach { r =>
              val bit = 1 << r.ticket.slot
              if(!r.known) unknown |= bit
              if(r.serial || query.serial) barrier |= bit
              if(query.known && r.known && r.store && (query.bytes intersect r.bytes).nonEmpty) {
                overlap |= bit; if(!r.data) waiting |= bit
              }
            }
          }
          val allow = q.exists(r => !r.store && r.known && r.bytes.nonEmpty &&
            (!r.serial || atCommit) && unknown == 0 && barrier == 0 && overlap == 0)
          assert(d.io.allowMemory.toBoolean == allow, s"permission cycle=$cycle ticket=$t")
          assert(d.io.unknown.toInt == unknown && d.io.barrier.toInt == barrier &&
            d.io.overlap.toInt == overlap && d.io.waitingData.toInt == waiting, s"reasons cycle=$cycle")
        }
        assert(d.io.occupied.toInt == records.indices.filter(records(_).nonEmpty).map(1 << _).sum)
        assert(d.io.canceled.toInt == records.indices.filter(n => records(n).exists(_.canceled)).map(1 << _).sum)
      }
    }
  }

  test("dispatch reservations, split address publication, data readiness, flush and reuse") {
    M68kSim().compile(new MemoryDependencyTracker(4)).doSim { d =>
      SimTimeout(10000)
      val cd = d.clockDomain; cd.forkStimulus(10)
      d.io.reserve.valid #= false; d.io.reserveSecond #= false
      d.io.address.valid #= false; d.io.dataReady.valid #= false
      d.io.commit.foreach(_.valid #= false); d.io.release.foreach(_.valid #= false)
      d.io.irreversible.valid #= false; d.io.flush #= false
      val lastRobId = (1 << m68k040.Global.ROB_ID_W_DEFAULT) - 1
      d.io.query.valid #= false; d.io.atCommit #= false
      cd.waitSampling(5)
      type Ticket = (Int, BigInt)
      def put(p: MemoryOrderTicket, t: Ticket): Unit = { p.slot #= t._1; p.generation #= t._2 }
      def tick(): Unit = { cd.waitSampling(); sleep(1) }
      def reserve(id0: Int, store0: Boolean, id1: Int = -1, store1: Boolean = false): Seq[Ticket] = {
        d.io.reserve.payload(0).robId #= id0; d.io.reserve.payload(0).store #= store0
        d.io.reserve.payload(0).serial #= false
        d.io.reserve.payload(1).robId #= math.max(id1, 0); d.io.reserve.payload(1).store #= store1
        d.io.reserve.payload(1).serial #= false; d.io.reserveSecond #= (id1 >= 0)
        d.io.reserve.valid #= true; sleep(1)
        assert(d.io.reserve.ready.toBoolean)
        val result = (0 until (if(id1 >= 0) 2 else 1)).map(n =>
          (d.io.tickets(n).slot.toInt, d.io.tickets(n).generation.toBigInt))
        tick(); d.io.reserve.valid #= false; sleep(1); result
      }
      def address(t: Ticket, line: Int, mask: Int, serial: Boolean = false,
                  lineB: Int = 0, maskB: Int = 0): Unit = {
        put(d.io.address.ticket, t); d.io.address.serial #= serial
        d.io.address.fragments(0).line #= line; d.io.address.fragments(0).mask #= mask
        d.io.address.fragments(1).line #= lineB; d.io.address.fragments(1).mask #= maskB
        d.io.address.valid #= true; tick(); d.io.address.valid #= false; sleep(1)
      }
      def query(t: Ticket): Unit = { put(d.io.query.payload, t); d.io.query.valid #= true; sleep(1) }
      def release(t: Ticket): Unit = {
        put(d.io.release(0).payload, t); d.io.release(0).valid #= true
        tick(); d.io.release(0).valid #= false; sleep(1)
      }
      val Seq(store, load) = reserve(lastRobId - 1, true, lastRobId, false)
      address(load, 10, 15); query(load)
      assert(d.io.unknown.toInt == (1 << store._1))
      assert(!d.io.allowMemory.toBoolean)
      address(store, 10, 0xf0)
      assert(d.io.allowMemory.toBoolean, "known disjoint store may still wait for its data")
      address(store, 10, 8)
      assert(!d.io.allowMemory.toBoolean)
      assert(d.io.waitingData.toInt == (1 << store._1))
      put(d.io.dataReady.payload, store); d.io.dataReady.valid #= true
      tick(); d.io.dataReady.valid #= false; sleep(1)
      assert(d.io.waitingData.toInt == 0)
      assert(!d.io.allowMemory.toBoolean, "data-ready overlap is for forwarding, never stale cache read")
      address(store, 11, 1, lineB = 10, maskB = 1)
      assert(!d.io.allowMemory.toBoolean, "second translated fragment aliases")
      address(store, 11, 1, lineB = 10, maskB = 0x10)
      assert(d.io.allowMemory.toBoolean)

      // ROB wrap: ID 0 is younger than the last two IDs.
      val wrapped = reserve(0, false).head
      address(wrapped, 11, 1); query(wrapped)
      assert(!d.io.allowMemory.toBoolean)
      val last = reserve(1, false).head
      sleep(1); assert(!d.io.reserve.ready.toBoolean)

      // Commit and flush on one edge preserve the store. Other reservations are
      // canceled but not reused until their owner has drained all responses.
      put(d.io.commit(0).payload, store); d.io.commit(0).valid #= true
      d.io.flush #= true; tick(); d.io.commit(0).valid #= false; d.io.flush #= false; sleep(1)
      assert(d.io.occupied.toInt == 15)
      assert(d.io.canceled.toInt == (15 & ~(1 << store._1)))
      assert(!d.io.queryPresent.toBoolean)
      assert(!d.io.reserve.ready.toBoolean)
      release(load); release(wrapped); release(last)
      val reused = reserve(lastRobId - 1, false).head
      assert(reused._1 == load._1 && reused._2 != load._2)
      address(reused, 11, 1); query(reused)
      assert(!d.io.allowMemory.toBoolean, "committed store is older even with identical reused ROB ID")
      // Late stale ticket cannot release or modify the new allocation.
      release(load); address(load, 100, 1)
      assert(d.io.queryPresent.toBoolean)
      assert(!d.io.allowMemory.toBoolean)
      release(store)
      assert(d.io.allowMemory.toBoolean)
      address(reused, 11, 1, serial = true)
      assert(!d.io.allowMemory.toBoolean)
      d.io.atCommit #= true; sleep(1); assert(d.io.allowMemory.toBoolean)

      // An irrevocable access survives repeated flushes; reset clears all slots.
      put(d.io.irreversible.payload, reused); d.io.irreversible.valid #= true
      tick(); d.io.irreversible.valid #= false
      d.io.flush #= true; tick(); tick(); d.io.flush #= false; sleep(1)
      assert(d.io.canceled.toInt == 0)
      assert(d.io.queryPresent.toBoolean)
      val afterIrreversible = reserve(lastRobId - 1, false).head
      address(afterIrreversible, 100, 1); query(afterIrreversible)
      assert(!d.io.allowMemory.toBoolean, "irrevocable barrier remains older after ROB ID reuse")
      assert(d.io.barrier.toInt == (1 << reused._1))
      cd.assertReset(); sleep(30); cd.deassertReset(); tick()
      assert(d.io.occupied.toInt == 0)
      assert(!d.io.queryPresent.toBoolean)
    }
  }
}
