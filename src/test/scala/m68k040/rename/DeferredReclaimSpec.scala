package m68k040.rename

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

class DeferredReclaimSpec extends AnyFunSuite {
  // NZVC, X and FPCC use the same 16/1 component specialization.
  for ((name, physical, architectural) <- Seq(
    ("integer", m68k040.Global.PHYS_INT_REGS_DEFAULT, m68k040.isa.Isa.ARCH_INT_REGS),
    ("NZVC/X/FPCC", 16, 1), ("FP", 16, 8)); width <- Seq(2, 4, 8, 16)) {
    test(s"$name $width-wide delayed reclaim: WAW, flush, wrap, full/empty and reset", VerilatorTest) {
      M68kSim().withVerilator.compile(Freelist(physical, architectural, 2, width)).doSim { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        dut.io.flush #= false
        dut.io.pop.foreach(_.take #= false)
        dut.io.push.foreach(_.valid #= false)
        cd.waitSamplingWhere(dut.io.popReady.toBoolean)
        sleep(1)

        val available = mutable.Set.empty[Int] ++ (architectural until physical)
        val inflight = mutable.Queue.empty[(Int, Int)] // architectural dest / allocated ID
        val committed = Array.tabulate(architectural)(identity)
        var pending = Vector.empty[Int]
        var cycles = 0
        def step(alloc: Int = 0, retire: Int = 0, flush: Boolean = false,
                 sparse: Boolean = false, illegalPush: Boolean = false): Unit = {
          assert(alloc <= 2 && retire <= math.min(width, inflight.size))
          assert(!flush || (alloc == 0 && retire == 0))
          assert(alloc == 0 || available.size >= 2)
          dut.io.flush #= flush
          dut.io.pop.foreach(_.take #= false)
          dut.io.push.foreach(_.valid #= false)
          val allocLanes = if (alloc == 1 && sparse) Seq(1) else 0 until alloc
          allocLanes.foreach(i => dut.io.pop(i).take #= true)
          sleep(1)
          val allocations = allocLanes.map(i => dut.io.pop(i).id.toInt)
          assert(allocations.distinct.size == alloc)
          allocations.foreach(id => assert(available.remove(id), s"allocated unavailable ID $id at $cycles"))
          // All destinations target architectural zero: maximally dense WAW chains.
          val frees = (0 until retire).map { i =>
            val (arch, id) = inflight.dequeue()
            val old = committed(arch)
            committed(arch) = id
            // Exercise every sparse position while retaining age order.
            val lane = if (sparse) width - retire + i else i
            dut.io.push(lane).valid #= true
            dut.io.push(lane).payload #= old
            old
          }.toVector
          allocations.foreach(id => inflight.enqueue((0, id)))
          if (illegalPush) {
            assert(flush)
            // Raw interface drops *new* pushes during flush, not pending ones.
            dut.io.push(0).valid #= true
            dut.io.push(0).payload #= committed(0)
          }
          pending.foreach(id => assert(available.add(id), s"double free $id at $cycles"))
          if (flush) {
            inflight.foreach { case (_, id) => assert(available.add(id)) }
            inflight.clear()
          }
          pending = frees
          cd.waitSampling()
          sleep(1)
          assert(dut.count.toInt == available.size,
            s"cycle $cycles: count=${dut.count.toInt} expected=${available.size}")
          assert(dut.io.popReady.toBoolean == (available.size >= 2))
          assert(available.size + inflight.size + pending.size == physical - architectural)
          assert(committed.forall(id => !available(id) && !pending.contains(id)))
          cycles += 1
        }

        step(alloc = 2)
        val before = dut.count.toInt
        step(retire = 2)
        assert(dut.count.toInt == before, "commit must not expose freed IDs immediately")
        step(flush = true, illegalPush = true) // drains both prior frees; rejects new push
        step(flush = true)
        step()
        // Exhaust available entries, then reclaim two. popReady must recover
        // only on the drain edge, not the architectural commit edge.
        while (available.size >= 2) step(alloc = 2)
        step(retire = 2)
        assert(!dut.io.popReady.toBoolean)
        step()
        assert(dut.io.popReady.toBoolean)
        step(flush = true)

        // A full batch contains allocations from more than one rename edge.
        // No intermediate WAW free may be lost when only the last map survives.
        val batchSize = math.min(width, (physical - architectural) / 2 * 2)
        for (_ <- 0 until batchSize / 2) step(alloc = 2)
        step(retire = batchSize, sparse = true)
        assert(pending.size == batchSize)
        step(flush = true)
        step(flush = true)

        // Full two-wide throughput across many pointer wraps, with no bubbles
        // in either commit or allocation once the two-entry stream is primed.
        step(alloc = 2)
        for (_ <- 0 until 128) step(alloc = 2, retire = 2)
        step(flush = true)

        val rng = new scala.util.Random(0x040040L)
        for (_ <- 0 until 2500) {
          val flush = rng.nextInt(19) == 0
          val alloc = if (flush || available.size < 2) 0 else rng.nextInt(3)
          val retire = if (flush) 0 else rng.nextInt(math.min(width, inflight.size) + 1)
          step(alloc, retire, flush, sparse = rng.nextBoolean())
        }
        step(flush = true)
        step(alloc = 2)
        step(retire = 2) // reset while both reclamation lanes are occupied
        cd.assertReset()
        dut.io.pop.foreach(_.take #= false)
        dut.io.push.foreach(_.valid #= false)
        sleep(40)
        cd.deassertReset()
        cd.waitSamplingWhere(dut.io.popReady.toBoolean)
        sleep(1)
        assert(dut.count.toInt == physical - architectural)
        assert(dut.reclaim.forall(!_.valid.toBoolean))
        val restored = mutable.Set.empty[Int]
        for (_ <- architectural until physical) {
          dut.io.pop(0).take #= true
          sleep(1)
          assert(restored.add(dut.io.pop(0).id.toInt))
          cd.waitSampling()
          sleep(1)
        }
        assert(restored.toSet == (architectural until physical).toSet)
      }
    }
  }
}
