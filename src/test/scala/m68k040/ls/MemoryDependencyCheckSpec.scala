package m68k040.ls

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class MemoryDependencyCheckSpec extends AnyFunSuite {
  private case class Fragment(line: Int, mask: Int) {
    def bytes: Set[Long] = (0 until 16).filter(b => ((mask >> b) & 1) != 0)
      .map(b => (line.toLong << 4) + b).toSet
  }
  private case class Record(older: Boolean = true, known: Boolean = true,
    store: Boolean = true, serial: Boolean = false, data: Boolean = true,
    a: Fragment = Fragment(1, 15), b: Fragment = Fragment(0, 0))

  test("physical byte dependency decisions match an independent byte-set model") {
    M68kSim().compile(new MemoryDependencyCheck(4)).doSim { d =>
      def drive(f: MemoryByteFragment, x: Fragment): Unit = { f.line #= x.line; f.mask #= x.mask }
      def check(a: Fragment, b: Fragment, rs: Seq[Record], serial: Boolean = false,
                atCommit: Boolean = false, valid: Boolean = true, known: Boolean = true): Boolean = {
        require(rs.size <= 4)
        val records = rs.padTo(4, Record(older = false))
        d.io.valid #= valid; d.io.addressKnown #= known
        d.io.serial #= serial; d.io.atCommit #= atCommit
        drive(d.io.fragments(0), a); drive(d.io.fragments(1), b)
        records.zipWithIndex.foreach { case (r, n) =>
          val p = d.io.records(n)
          p.older #= r.older; p.addressKnown #= r.known
          p.store #= r.store; p.serial #= r.serial; p.dataReady #= r.data
          drive(p.fragments(0), r.a); drive(p.fragments(1), r.b)
        }
        sleep(1)
        val bytes = a.bytes ++ b.bytes
        val active = valid && known && bytes.nonEmpty
        val unknown = records.map(r => valid && r.older && !r.known)
        val barrier = records.map(r => valid && r.older && (r.serial || serial))
        val overlap = records.map(r => active && r.older && r.known && r.store &&
          bytes.intersect(r.a.bytes ++ r.b.bytes).nonEmpty)
        val waiting = overlap.zip(records).map { case (hit, r) => hit && !r.data }
        def mask(bs: Seq[Boolean]): Int = bs.zipWithIndex.map { case (v, n) => if(v) 1 << n else 0 }.sum
        assert(d.io.unknown.toInt == mask(unknown))
        assert(d.io.barrier.toInt == mask(barrier))
        assert(d.io.overlap.toInt == mask(overlap))
        assert(d.io.waitingData.toInt == mask(waiting))
        val allow = active && (!serial || atCommit) &&
          !(unknown.contains(true) || barrier.contains(true) || overlap.contains(true))
        assert(d.io.allowMemory.toBoolean == allow)
        allow
      }
      val load = Fragment(1, 0x000f); val absent = Fragment(0, 0)
      assert(check(load, absent, Seq.empty))
      assert(!check(load, absent, Seq(Record(known = false))))
      assert(check(load, absent, Seq(Record(a = Fragment(1, 0x00f0), data = false))))
      assert(!check(load, absent, Seq(Record(a = Fragment(1, 0x0008), data = false))))
      assert(!check(load, absent, Seq(Record(data = true)))) // ready data is NOT cache-read permission
      assert(check(load, absent, Seq(Record(store = false))))
      assert(!check(load, absent, Seq(Record(store = false, serial = true))))
      assert(!check(load, absent, Seq.empty, serial = true))
      assert(check(load, absent, Seq.empty, serial = true, atCommit = true))
      assert(!check(load, absent, Seq(Record(store = false)), serial = true, atCommit = true))
      // Independently translated page-crossing halves, and differing byte lanes.
      assert(!check(Fragment(3, 0x8000), Fragment(99, 7),
        Seq(Record(a = Fragment(77, 0x8000), b = Fragment(99, 2)))))
      assert(check(Fragment(3, 0x8000), Fragment(99, 7),
        Seq(Record(a = Fragment(77, 0x8000), b = Fragment(99, 8)))))
      assert(check(load, absent, Seq(Record(older = false, known = false, serial = true))))
      assert(!check(absent, absent, Seq.empty))
      val rng = new scala.util.Random(0x6804001)
      def fragment() = Fragment(rng.nextInt(8), rng.nextInt(65536))
      for (_ <- 0 until 4096) {
        val records = Seq.fill(4)(Record(rng.nextBoolean(), rng.nextBoolean(),
          rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(), fragment(), fragment()))
        check(fragment(), fragment(), records, rng.nextBoolean(), rng.nextBoolean(),
          rng.nextBoolean(), rng.nextBoolean())
      }
    }
  }
}
