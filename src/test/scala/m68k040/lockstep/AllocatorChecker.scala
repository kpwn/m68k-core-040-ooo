package m68k040.lockstep

import m68k040.rename.{Freelist, RenameStage}
import spinal.core.sim._
import scala.collection.mutable

/** Sim-time shadow busy-vector over a physical register file, asserting DOUBLE ALLOC /
  * DOUBLE FREE against the `Freelist`'s own pop/push ports.
  *
  * Port of NaxRiscv's `NaxAllocatorChecker`
  * (`thirdparty/NaxRiscv/src/test/cpp/naxriscv/src/main.cpp:1312-1355`). Upstream has it
  * behind `ALLOCATOR_CHECKS?=no` (`makefile:19`); it is enabled by DEFAULT here, because
  * tasks #176 / #194 / #200 in this project were all the same bug class
  * (rename-exposure / physical-register reuse) and each was found only as a lock-step
  * divergence hundreds of instructions downstream of the actual corruption.
  *
  * ── One difference from upstream, and why ──────────────────────────────────────────
  * NaxRiscv's checker has no flush handling. Ours needs it: `Freelist` recovers from a
  * mispredict by POINTER ROLLBACK (`head := commHead`, `Freelist.scala:124-131`), which
  * returns every still-speculative pop to the pool in one cycle without any push port
  * firing. The shadow therefore tracks which pops are still speculative:
  *
  *  - pop  (take && initDone && !flush): assert !busy, set busy, enqueue as speculative.
  *  - push (valid && initDone && !flush): assert busy, clear busy, and retire ONE
  *    speculative pop -- the RTL's `commHead += pushCount` says committed pops and
  *    pushes advance 1:1 (`Freelist.scala:191-193`).
  *  - flush: free every still-speculative pop (exactly `head - commHead` of them).
  *
  * Note the push payload is the retiring writer's OLD pdst, which is unrelated to the id
  * being retired from the speculative queue; the dequeue is pure accounting.
  */
final class FreelistShadow(val name: String, physCount: Int, archCount: Int) {
  private val busy = Array.fill(physCount)(false)
  // Ids 0..archCount-1 are the initial committed arch mapping: allocated, never free.
  for (i <- 0 until archCount) busy(i) = true
  private val spec = mutable.Queue[Int]()
  val errors = mutable.ArrayBuffer[String]()

  private def err(msg: String): Unit = if (errors.size < 8) errors += s"[$name] $msg"

  def onCycle(cycle: Long, initDone: Boolean, flush: Boolean,
              pops: Seq[(Boolean, Int)], pushes: Seq[(Boolean, Int)]): Unit = {
    if (!initDone) return
    if (flush) {
      while (spec.nonEmpty) { val id = spec.dequeue(); busy(id) = false }
      return   // pops/pushes are architecturally impossible on a flush cycle (RTL gates them)
    }
    // Free before alloc within a cycle, matching NaxRiscv's preCycle ordering: a
    // same-cycle free+alloc of one id is legal (the RTL cannot do it -- a pushed id lands
    // at `tail`, a popped id comes from `head` -- but the ordering makes the checker
    // insensitive to that being true).
    for ((v, id) <- pushes if v) {
      if (id >= physCount) err(f"push of out-of-range phys id $id at cycle $cycle")
      else {
        if (!busy(id)) err(f"Double free: phys $id freed while already free (cycle $cycle)")
        busy(id) = false
        if (spec.nonEmpty) spec.dequeue()
      }
    }
    for ((t, id) <- pops if t) {
      if (id >= physCount) err(f"pop of out-of-range phys id $id at cycle $cycle")
      else {
        if (busy(id)) err(f"Double alloc: phys $id allocated while already busy (cycle $cycle)")
        busy(id) = true
        spec.enqueue(id)
      }
    }
  }
}

/** Binds `FreelistShadow`s to the five freelists a `RenameStage` owns (§7.3 of the
  * NaxRiscv comparison: we rename five register classes where NaxRiscv renames two). */
final class AllocatorChecker(ren: RenameStage) {
  private case class Bound(shadow: FreelistShadow, fl: Freelist)
  // Sizes are read FROM THE DUT's own Freelist components, never hardcoded.
  //
  // They used to be literals, and the int one said 50 while Config.physInt had
  // moved to 54 -- so every legal allocation of phys id 50..53 was reported as
  // "pop/push of out-of-range phys id", failing whichever tests happened to
  // allocate that far.  That is why the failures looked random and spanned
  // completely unrelated families (bitfield mem-DYNAMIC, p163 pic-header, CAS,
  // stale-fwd): the only thing they shared was allocating deep enough to reach
  // 50.  Deriving from `fl.physCount`/`fl.archCount` makes the checker track any
  // future PRF resize by construction.
  private def shadowFor(name: String, fl: Freelist) =
    Bound(new FreelistShadow(name, fl.physCount, fl.archCount), fl)

  private val bound: Seq[Bound] = Seq(
    shadowFor("int",  ren.logic.intFree),
    shadowFor("nzvc", ren.logic.nzvcFree),
    shadowFor("x",    ren.logic.xFree),
    shadowFor("fp",   ren.logic.fpFree),
    shadowFor("fpcc", ren.logic.fpccFree))

  private var cycle = 0L

  /** Call once per sampled cycle. */
  def onCycle(): Unit = {
    cycle += 1
    for (b <- bound) {
      val fl = b.fl
      b.shadow.onCycle(
        cycle,
        initDone = fl.initDone.toBoolean,
        flush    = fl.io.flush.toBoolean,
        pops     = fl.io.pop.map(p => (p.take.toBoolean, p.id.toInt)).toSeq,
        pushes   = fl.io.push.map(p => (p.valid.toBoolean, p.payload.toInt)).toSeq)
    }
  }

  def errors: Seq[String] = bound.flatMap(_.shadow.errors)
  def ok: Boolean = errors.isEmpty
}
