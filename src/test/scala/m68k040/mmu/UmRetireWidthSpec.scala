package m68k040.mmu

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UmRetireWidthSpec extends AnyFunSuite {
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
        drain(0x201); drain(0x202)
        cd.waitSampling(3); sleep(1)
        assert(!d.io.full.toBoolean && !d.io.drain.valid.toBoolean)
      }
      alloc(2, 0x300, born = true)
      cd.assertReset(); sleep(40); cd.deassertReset(); cd.waitSampling(3); sleep(1)
      assert(!d.io.full.toBoolean && !d.io.drain.valid.toBoolean)
    }
  }
}
