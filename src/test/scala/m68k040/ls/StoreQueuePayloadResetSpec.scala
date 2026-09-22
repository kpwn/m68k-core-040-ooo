package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.cache.CacheMode
import m68k040.isa.Size
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** Payload ownership test, not a replacement for SQ ordering/geometry suites. */
class StoreQueuePayloadResetSpec extends AnyFunSuite {
  class CheckedQueue(reserved: Boolean, bypass: Boolean)
      extends StoreQueue(8, subwordForwarding = true, reserveLateStore = reserved,
        forwardOnPublish = bypass) {
    Seq(paddrs, paddrBs, vaddrAs, vaddrBs).foreach(_.foreach(_.simPublic()))
    Seq(datas, maskAs, maskBs).foreach(_.foreach(_.simPublic()))
    if (reserved) dataReady.foreach(_.simPublic())
  }
  case class Entry(id: Int, pa: Long, pb: Long, va: Long, vb: Long, data: Long,
                   bytes: Int, mode: SpinalEnumElement[CacheMode.type],
                   precise: Boolean = false, ready: Boolean = true) {
    def off: Int = (pa & 15).toInt
    def na: Int = math.min(bytes, 16 - off)
    def nb: Int = bytes - na
    def split: Boolean = nb != 0
    def ma: Int = ((1 << na) - 1) << off
    def mb: Int = (1 << nb) - 1
  }
  case class Query(id: Int = 9, pa: Long = 0x100, bytes: Int = 4,
                   inhibited: Boolean = false, split: Boolean = false, pb: Long = 0)
  def size(bytes: Int): SpinalEnumElement[Size.type] = bytes match {
    case 1 => Size.BYTE
    case 2 => Size.WORD
    case 4 => Size.LONG
  }

  for ((reserved, bypass) <- Seq((false, false), (true, false), (true, true))) {
    test(s"poisoned SQ payload ownership reserved=$reserved bypass=$bypass", VerilatorTest) {
      M68kSim().compile(new CheckedQueue(reserved, bypass)).doSim { d =>
        SimTimeout(2000000)
        val cd = d.clockDomain
        val rng = new scala.util.Random(0x53515253L)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val shadow = Array.fill[Option[Entry]](8)(None)
        var cycles = 0; var resets = 0; var accepted = 0; var forwards = 0
        var faults = 0; var orphanCompletions = 0; var publishHits = 0
        var headId = 0
        def record(s: String): Unit = digest.update((s + "\n").getBytes("UTF-8"))
        def putAlloc(e: Entry): Unit = {
          val a = d.io.alloc.payload
          a.robId #= e.id; a.paddr #= e.pa; a.paddrB #= e.pb
          a.vaddr #= e.va; a.vaddrB #= e.vb; a.data #= e.data
          a.size #= size(e.bytes); a.nbytesA #= e.na; a.nbytesB #= e.nb
          a.useStrbA #= e.split; a.validB #= e.split
          a.cacheMode #= e.mode; a.cacheModeB #= e.mode
          a.supervisor #= true; a.precise #= e.precise
        }
        def defaults(): Unit = {
          d.io.alloc.valid #= false
          putAlloc(Entry(0, 0x100, 0x900, 0x10100, 0x20900, 0, 4, CacheMode.COPYBACK))
          d.io.commit.valid #= false; d.io.commit.payload #= 0
          d.io.commitB.valid #= false; d.io.commitB.payload #= 0
          d.io.flush #= false; d.io.drain.ready #= false
          d.io.drainAck #= false; d.io.drainErr #= false
          d.io.robHeadIn #= headId; d.io.robHeadValidIn #= true
          d.io.irqPreemptPendingIn #= false; d.io.barrier.robId #= ((headId + 9) & 31)
          d.io.fwd.query.robId #= 9; d.io.fwd.query.paddr #= 0x100
          d.io.fwd.query.size #= Size.LONG; d.io.fwd.query.splitB #= false
          d.io.fwd.query.paddrB #= 0; d.io.fwd.query.inhibited #= false
          if (reserved) {
            d.io.reserveOnly #= false; d.io.publish.valid #= false
            d.io.publish.slot #= 0; d.io.publish.robId #= 0; d.io.publish.data #= 0
          }
        }
        def poisonUnowned(): Unit = {
          for (i <- 0 until 8 if !d.valids(i).toBoolean) {
            val p = 0xa5000000L | (resets.toLong << 16) | ((cycles & 255).toLong << 8) | i
            d.paddrs(i) #= p; d.paddrBs(i) #= (p ^ 0x11000000L)
            d.vaddrAs(i) #= (p ^ 0x22000000L); d.vaddrBs(i) #= (p ^ 0x33000000L)
            d.datas(i) #= (p ^ 0x44000000L)
            d.maskAs(i) #= (0x5a5a ^ i); d.maskBs(i) #= (0xa5a5 ^ i)
          }
        }
        def checkOwned(): Unit = {
          for (i <- 0 until 8 if d.valids(i).toBoolean) {
            val e = shadow(i).getOrElse(fail(s"SQ exposed unallocated row $i"))
            val got = Seq(d.paddrs(i).toLong, d.paddrBs(i).toLong, d.vaddrAs(i).toLong,
              d.vaddrBs(i).toLong, d.datas(i).toLong, d.maskAs(i).toLong, d.maskBs(i).toLong)
            val expected = Seq(e.pa, e.pb, e.va, e.vb, e.data, e.ma.toLong, e.mb.toLong)
            assert(got == expected,
              s"SQ poisoned payload became owned: cycle=$cycles row=$i expected=$expected got=$got")
            if (reserved) assert(d.dataReady(i).toBoolean == e.ready)
          }
        }
        def resetAndPoison(): Unit = {
          cd.waitFallingEdge(); defaults(); cd.assertReset(); sleep(30)
          cd.waitFallingEdge(); cd.deassertReset(); sleep(1)
          for (i <- shadow.indices) shadow(i) = None
          resets += 1; poisonUnowned(); sleep(1)
          assert(d.valids.forall(!_.toBoolean) && d.acceptedHalves.toInt == 0)
          assert(d.io.empty.toBoolean && !d.io.drain.valid.toBoolean)
          assert(!d.io.fwd.rsp.hit.toBoolean && !d.io.sqCompletion.valid.toBoolean)
          record(s"reset:$resets")
        }
        def step(alloc: Option[Entry] = None, publish: Option[(Int, Long)] = None,
                 commit: Option[Int] = None, flush: Boolean = false,
                 ready: Boolean = false, ack: Boolean = false, error: Boolean = false,
                 query: Query = Query()): Unit = {
          cd.waitFallingEdge(); defaults()
          val slot = d.tail.toInt
          alloc.foreach { e =>
            assert(!d.io.full.toBoolean)
            putAlloc(e); d.io.alloc.valid #= true
            if (reserved) d.io.reserveOnly #= !e.ready
          }
          publish.foreach { case (i, data) =>
            val e = shadow(i).get
            d.io.publish.valid #= true; d.io.publish.slot #= i
            d.io.publish.robId #= e.id; d.io.publish.data #= data
          }
          commit.foreach { id => d.io.commit.valid #= true; d.io.commit.payload #= id }
          d.io.flush #= flush; d.io.drain.ready #= ready
          d.io.drainAck #= ack; d.io.drainErr #= error
          d.io.fwd.query.robId #= query.id; d.io.fwd.query.paddr #= query.pa
          d.io.fwd.query.size #= size(query.bytes); d.io.fwd.query.inhibited #= query.inhibited
          d.io.fwd.query.splitB #= query.split; d.io.fwd.query.paddrB #= query.pb
          sleep(1)
          val hit = d.io.fwd.rsp.hit.toBoolean
          if (hit) {
            // Independently select the youngest eligible exact/subword producer.
            val eligible = (0 until 8).filter { i =>
              d.valids(i).toBoolean && (d.committed(i).toBoolean ||
                (((shadow(i).get.id - headId) & 31) < ((query.id - headId) & 31)))
            }
            assert(!query.inhibited && !eligible.exists(i => shadow(i).get.mode == CacheMode.INHIBITED))
            def overlaps(e: Entry): Boolean = {
              val qna = math.min(query.bytes, 16 - (query.pa & 15).toInt)
              val qbytes = (0 until qna).map(query.pa + _) ++
                (if (query.split) (0 until query.bytes - qna).map(query.pb + _) else Seq.empty)
              val ebytes = (0 until e.na).map(e.pa + _) ++ (0 until e.nb).map(e.pb + _)
              qbytes.exists(ebytes.contains)
            }
            val candidates = eligible.filter(i => overlaps(shadow(i).get))
            assert(candidates.nonEmpty, "SQ forwarded unowned data")
            val i = candidates.maxBy(i => (i - d.head.toInt) & 7)
            val e = shadow(i).get
            val exact = e.pa == query.pa && e.bytes == query.bytes
            val subword = e.bytes == 4 && (e.pa & 3) == 0 && query.pa >= e.pa &&
              query.pa + query.bytes <= e.pa + 4
            assert(!e.split && !query.split && (exact || subword))
            val publishing = bypass && !flush && publish.exists(_._1 == i)
            assert(e.ready || publishing, "SQ forwarded an unfilled reservation")
            val data = if (publishing) publish.get._2 else e.data
            val expected = if (exact) data else
              (data >>> (8 * (4 - (query.pa - e.pa).toInt - query.bytes))) & ((1L << (8 * query.bytes)) - 1)
            assert(d.io.fwd.rsp.data.toLong == expected, "SQ qualified forward payload mismatch")
            forwards += 1; if (publishing) publishHits += 1
          }
          if (d.io.drain.valid.toBoolean) {
            val e = shadow(d.sendPtr.toInt).get
            val second = d.sendPhaseB.toBoolean
            assert(e.ready && (!second || e.split))
            val p = d.io.drain.payload
            assert(p.paddr.toLong == (if (second) e.pb else e.pa))
            assert(p.data.toLong == (if (second) 0L else e.data))
            val strobe = if (second) e.mb else e.ma
            var line = BigInt(0)
            for (k <- 0 until e.bytes if (e.off + k >= 16) == second) {
              val lane = (e.off + k) & 15
              line |= BigInt((e.data >>> (8 * (e.bytes - k - 1))) & 255) << (8 * lane)
            }
            assert(p.strb.toInt == strobe && p.lineData.toBigInt == line)
            if (ready) accepted += 1
          }
          if (d.io.sqFaultCompletion.valid.toBoolean) {
            val e = shadow(d.head.toInt).get
            assert(error && ack && e.precise)
            assert(d.io.sqFaultCompletion.payload.faultAddr.toLong ==
              (if (d.ackPhaseB.toBoolean) e.vb else e.va))
            faults += 1
          }
          if (d.io.sqCompletion.valid.toBoolean && d.io.sqCompletionOrphan.toBoolean)
            orphanCompletions += 1
          val state = Seq(d.head.toInt, d.tail.toInt, d.sendPtr.toInt, d.acceptedHalves.toInt,
            d.valids.map(v => if (v.toBoolean) 1 else 0).mkString,
            d.committed.map(v => if (v.toBoolean) 1 else 0).mkString,
            d.sendPhaseB.toBoolean, d.ackPhaseB.toBoolean, d.io.full.toBoolean,
            d.io.empty.toBoolean, hit, d.io.fwd.rsp.stall.toBoolean, d.io.fwd.rsp.serial.toBoolean,
            if (hit) d.io.fwd.rsp.data.toLong else 0L,
            d.io.drain.valid.toBoolean,
            if (d.io.drain.valid.toBoolean) d.io.drain.payload.paddr.toLong else 0L,
            if (d.io.drain.valid.toBoolean) d.io.drain.payload.lineData.toBigInt else BigInt(0),
            d.io.sqCompletion.valid.toBoolean, d.io.sqFaultCompletion.valid.toBoolean,
            d.io.preciseDrainBusy.toBoolean, d.io.flushKeptPrecise.toBoolean,
            d.io.barrier.olderStore.toBoolean, d.io.barrier.olderInhibitedStore.toBoolean)
          record(s"$cycles:${state.mkString(":")}")
          if (!flush) {
            publish.foreach { case (i, data) => shadow(i) = Some(shadow(i).get.copy(data = data, ready = true)) }
            alloc.foreach(e => shadow(slot) = Some(e))
          }
          cd.waitRisingEdge(); sleep(1); cycles += 1
          checkOwned(); poisonUnowned(); sleep(1)
        }
        def drainAll(query: Query): Unit = {
          var n = 0
          while (!d.io.empty.toBoolean && n < 300) {
            step(ready = rng.nextInt(4) != 0,
              ack = d.acceptedHalves.toInt != 0 && rng.nextInt(3) != 0, query = query)
            n += 1
          }
          assert(d.io.empty.toBoolean, "SQ drain did not finish")
        }
        cd.forkStimulus(10); defaults(); cd.waitSampling(10); resetAndPoison()
        for (round <- 0 until 96) {
          if (round != 0 && round % 16 == 0) resetAndPoison()
          headId = (round * 7) & 31
          val entries = (0 until 8).map { i =>
            val bytes = if (i % 3 == 0) 4 else if (i % 3 == 1) 2 else 1
            val off = if (i == 0) 0 else if (i % 2 == 0) 15 else rng.nextInt(16)
            Entry((headId + i) & 31, 0x100L + 16 * (i % 3) + off,
              0x900L + 16 * i, 0x10100L + 16 * i + off, 0x20900L + 16 * i,
              rng.nextLong() & 0xffffffffL, bytes,
              if (i % 2 == 0) CacheMode.COPYBACK else CacheMode.WRITETHROUGH,
              ready = !reserved || i % 2 != 0)
          }
          val slots = entries.map { e => val slot = d.tail.toInt; step(alloc = Some(e)); slot }
          for ((e, i) <- entries.zipWithIndex) {
            val q = Query((headId + 9) & 31, e.pa, e.bytes, split = e.split, pb = e.pb)
            step(query = q)
            if (!e.ready) step(publish = Some(slots(i) -> (rng.nextLong() & 0xffffffffL)), query = q)
            step(query = q); step(query = q.copy(inhibited = true))
          }
          // Retain a committed prefix, squash younger entries, then drain with stalls.
          val keep = 2 + round % 7
          for (i <- 0 until keep) step(commit = Some(entries(i).id))
          step(flush = true)
          assert(d.valids.count(_.toBoolean) == keep)
          drainAll(Query((headId + 9) & 31, entries.head.pa, 1))
        }
        if (reserved) {
          step(alloc = Some(Entry(3, 0x100, 0x900, 0x10100, 0x20900,
            0xdeadbeefL, 4, CacheMode.COPYBACK, ready = false)))
          resetAndPoison() // Cancel an unfilled reservation and its publication owner.
        }
        for (orphan <- Seq(false, true); faultHalf <- Seq(-1, 0, 1)) {
          headId = 29
          val e = Entry(29, 0x10f, 0x900, 0x1234500f, 0x6789a000,
            0x89abcdefL, 4, CacheMode.INHIBITED, precise = true)
          step(alloc = Some(e)); step(ready = true)
          assert(d.acceptedHalves.toInt == 1)
          if (orphan) { step(flush = true); headId = 4 }
          step(ack = true, error = faultHalf == 0)
          if (faultHalf != 0) {
            step(ready = true); assert(d.acceptedHalves.toInt == 1)
            step(ack = true, error = faultHalf == 1)
          }
          assert(d.io.empty.toBoolean)
        }
        // Warm reset cancels accepted ownership under the existing paired-reset contract.
        headId = 29
        step(alloc = Some(Entry(29, 0x10f, 0x900, 0x1010f, 0x20900,
          0x12345678L, 4, CacheMode.INHIBITED, precise = true)))
        step(ready = true); assert(d.acceptedHalves.toInt == 1)
        resetAndPoison(); step(ready = true)
        assert(d.io.empty.toBoolean && !d.io.drain.valid.toBoolean)
        assert(accepted > 500 && forwards > 100 && faults == 4 && orphanCompletions == 3)
        if (bypass) assert(publishHits > 0)
        val hash = digest.digest().map(b => f"${b & 255}%02x").mkString
        println(s"SQ_POISON_TRACE reserved=$reserved bypass=$bypass cycles=$cycles resets=$resets accepted=$accepted forwards=$forwards faults=$faults orphan=$orphanCompletions publish=$publishHits sha256=$hash")
      }
    }
  }
}
