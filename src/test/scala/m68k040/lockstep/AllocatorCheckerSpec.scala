package m68k040.lockstep

import org.scalatest.funsuite.AnyFunSuite

class AllocatorCheckerSpec extends AnyFunSuite {
  test("delayed-reclaim checker rejects reuse on the drain edge") {
    val s = new FreelistShadow("test", 8, 1)
    s.onCycle(0, true, false, Seq(true -> 1), Seq.empty)
    s.onCycle(1, true, false, Seq.empty, Seq(true -> 0))
    s.onCycle(2, true, false, Seq(true -> 0), Seq.empty)
    assert(s.errors.exists(_.contains("Double alloc")))
  }

  test("pending committed frees drain on flush; speculative allocations return once") {
    val s = new FreelistShadow("test", 8, 1)
    s.onCycle(0, true, false, Seq(true -> 1, true -> 2), Seq.empty)
    s.onCycle(1, true, false, Seq.empty, Seq(true -> 0))
    s.onCycle(2, true, true, Seq.empty, Seq.empty)
    s.onCycle(3, true, true, Seq.empty, Seq.empty)
    s.onCycle(4, true, false, Seq(true -> 0, true -> 2), Seq.empty)
    assert(s.errors.isEmpty)
    // ID 1 belongs to the new committed mapping, not to rollback's free set.
    s.onCycle(5, true, false, Seq(true -> 1), Seq.empty)
    assert(s.errors.exists(_.contains("Double alloc")))
  }

  test("reset drops pending frees and restores identity allocation state") {
    val s = new FreelistShadow("test", 8, 1)
    s.onCycle(0, true, false, Seq(true -> 1), Seq.empty)
    s.onCycle(1, true, false, Seq.empty, Seq(true -> 0))
    s.onCycle(2, false, false, Seq.empty, Seq.empty)
    s.onCycle(3, true, false, Seq(true -> 1), Seq.empty)
    assert(s.errors.isEmpty)
    s.onCycle(4, true, false, Seq(true -> 0), Seq.empty)
    assert(s.errors.exists(_.contains("Double alloc")))
  }
}
