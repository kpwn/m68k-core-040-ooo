package m68k040.rename

import spinal.core._
import spinal.lib._

/** Pointer-based multi-port physical-register freelist.
  *
  * Circular buffer holding FREE phys-ids.
  * Initially free = ids archCount .. physCount-1.
  * Ids 0..archCount-1 are the initial committed arch mapping, reserved.
  *
  * Architectural commit advances commHead immediately; old physical IDs become
  * available one cycle later through the registered reclamation lanes. Flush
  * rolls head back to commHead while still draining already-committed frees.
  * Reset alone initializes the ring to the identity mapping's complement.
  */
case class Freelist(
    physCount: Int,
    archCount: Int,
    popPorts:  Int,
    pushPorts: Int
) extends Component {

  val idW     = log2Up(physCount)
  val ptrW    = log2Up(physCount)
  val countW  = log2Up(physCount + 1)
  val freeN   = physCount - archCount   // initial free count

  val io = new Bundle {
    val pop      = Vec.fill(popPorts)(new Bundle {
      val take = in  Bool()
      val id   = out UInt(idW bits)
    })
    val popReady = out Bool()
    val push     = Vec.fill(pushPorts)(slave(Flow(UInt(idW bits))))
    val flush    = in Bool()
  }

  // ── Circular RAM ──────────────────────────────────────────────────────────
  // Depth = 2^ptrW (NOT physCount): the ptrW-bit head/tail/commHead pointers wrap
  // at 2^ptrW, so the RAM must be that deep or pointers past physCount address out
  // of range. The ring only ever holds <= freeN free ids, so the extra capacity is
  // unused — it just keeps the binary pointer wrap consistent with the RAM depth.
  val ram = Mem(UInt(idW bits), 1 << ptrW)

  // ── Pointers ──────────────────────────────────────────────────────────────
  val head  = Reg(UInt(ptrW   bits)) init 0
  val tail  = Reg(UInt(ptrW   bits)) init 0
  val count = Reg(UInt(countW bits)) init 0
  spinal.core.sim.SimPublic(head, tail, count)   // sim-only debug (bf3c bring-up)
  // ALLOCATOR CHECKER (2026-09-04, NaxRiscv comparison item 4 / `NaxAllocatorChecker`,
  // main.cpp:1312-1355): a sim-time shadow busy-vector needs the allocator's OWN
  // alloc/free ports, not a downstream proxy. `io.*` of a child Component is not
  // reachable from a Verilator sim without this. Sim-only name preservation, zero
  // synthesis cost -- same convention as the head/tail/count line above.
  spinal.core.sim.SimPublic(io.pop, io.push, io.flush, io.popReady)
  val dbgPushAddr = Vec(UInt(ptrW bits), pushPorts)   // sim-only debug (bf3c bring-up)
  dbgPushAddr.foreach(_ := 0)
  dbgPushAddr.allowOverride
  spinal.core.sim.SimPublic(dbgPushAddr)

  // ── Init counter ──────────────────────────────────────────────────────────
  // After reset: fill ram[0..freeN-1] with ids archCount..physCount-1, one per cycle.
  val initDone    = Reg(Bool()) init False
  val initCounter = Reg(UInt(ptrW bits)) init 0   // index into ram (0..freeN-1)
  // Sim-only (allocator checker): the shadow busy-vector must ignore every cycle before
  // the power-on RAM fill completes, since pops/pushes are architecturally impossible then.
  spinal.core.sim.SimPublic(initDone)

  // initHead/initTail/initCount are the pointer values after init completes.
  // They are constants derived from the parameters.
  val initHeadVal  = U(0, ptrW bits)
  val initTailVal  = U(freeN, ptrW bits)
  val initCountVal = U(freeN, countW bits)

  // ── Committed-pop pointer (mispredict-recovery rollback) ───────────────────
  // `head` advances on SPECULATIVE rename pops; `commHead` mirrors it but advances
  // only as popped dsts RETIRE (their uops commit) — the same count as the commit
  // pushes, since every committed int/flag writer both frees its old pdst (push)
  // AND makes its newly-popped pdst permanent. On a mispredict flush we roll the
  // freelist back to its COMMITTED state by returning every in-flight speculative
  // pop (head - commHead) to the pool, also draining the pending committed frees.
  //
  // Recovery needs no RAM rebuild or multi-cycle re-init stall and makes
  // NO identity assumption about the committed mapping. The previous flush reset to
  // the INITIAL pool (free = archCount..physCount-1), which is only correct while
  // the committed mapping is identity — after real commits the committed RAT owns
  // non-identity physregs, so resetting wrongly freed live committed regs and a
  // later pop handed one out (clobbering an operand). That broke ANY recovery whose
  // committed mapping had drifted from identity (e.g. a loop past its 1st mispredict).
  val commHead = Reg(UInt(ptrW bits)) init 0

  // Already-committed resources: flush blocks new input, but MUST NOT discard
  // this stage's output. See docs/deferred-register-reclamation.md.
  val reclaim = Vec.fill(pushPorts)(Flow(UInt(idW bits)))
  for (j <- 0 until pushPorts) {
    reclaim(j).valid := RegNext(initDone && !io.flush && io.push(j).valid) init False
    reclaim(j).payload := RegNextWhen(io.push(j).payload,
      initDone && !io.flush && io.push(j).valid)
  }
  spinal.core.sim.SimPublic(reclaim, commHead)
  val pcW = log2Up(pushPorts + 1)
  val commitCount = io.push.map(_.valid.asUInt.resize(pcW)).reduce(_ +^ _).resize(pcW)
  val reclaimCount = reclaim.map(_.valid.asUInt.resize(pcW)).reduce(_ +^ _).resize(pcW)

  // Power-on RAM fill ONLY (driven by reset's !initDone, NOT by flush): write one id
  // per cycle into ram[0..freeN-1]. Flush no longer clears initDone.
  //
  // GOTCHA (silent-corruption class, root of the bf3c freelist double-alloc): SpinalHDL
  // Mem.write with an EXPLICIT `enable` REPLACES the surrounding when-condition — it is
  // NOT ANDed with it. The old `ram.write(..., enable = True)` INSIDE when(!initDone)
  // therefore wrote EVERY cycle forever: after init, initCounter parks at 0, so ram[0]
  // was stomped with id `archCount` each cycle — any push landing at slot 0 (every 2^ptrW
  // pushes, at the tail wraparound) was destroyed, and the next pop of slot 0 handed out
  // id `archCount` while it was live = a physreg double-allocation (two in-flight writers
  // on one physical register -> stale-operand corruption). Same class for the push writes
  // below (their when(initDone && !io.flush) was equally ignored). ALL Mem writes here
  // now carry their COMPLETE condition in the explicit enable, at top scope.
  ram.write(
    address = initCounter.resized,
    data    = (U(archCount, idW bits) + initCounter).resized,
    enable  = !initDone
  )
  when(!initDone) {
    when(initCounter === U(freeN - 1)) {
      initDone    := True
      initCounter := 0
      head        := initHeadVal
      tail        := initTailVal
      count       := initCountVal
      commHead    := initHeadVal
    } otherwise {
      initCounter := initCounter + 1
    }
  }

  // Mispredict flush: pointer-only rollback to committed state (init has priority).
  // commHead and initDone are unchanged. Pending frees still advance tail and
  // write RAM below, even on this recovery edge.
  when(initDone && io.flush) {
    // Squash undoes all SPECULATIVE pops: return to the committed head. Post-squash
    // free count is ALWAYS freeN (exactly archCount regs stay committed-allocated),
    // Before this edge tail + reclaimCount - commHead == freeN. Draining the
    // pending frees below restores tail-head == freeN after the recovery edge.
    head  := commHead
    count := initCountVal
  }

  // ── popReady ──────────────────────────────────────────────────────────────
  io.popReady := initDone && (count >= U(popPorts, countW bits))

  // ── Async reads (pop outputs) ─────────────────────────────────────────────
  // Compact the take mask: pop(k) reads from head + (number of takes among ports 0..k-1).
  // This ensures each asserted take consumes a distinct id regardless of which ports fire.
  // Non-asserted ports' id outputs are don't-care (the consumer ignores them when take=false).
  for (k <- 0 until popPorts) {
    val lowerTakes =
      if (k == 0) U(0, log2Up(popPorts + 1) bits)
      else (0 until k).map(j => io.pop(j).take.asUInt.resize(log2Up(popPorts + 1))).reduce(_ +^ _)
    io.pop(k).id := ram.readAsync((head + lowerTakes.resized).resized)
  }

  // ── Pop / architectural-commit updates (not reclamation) ────────────────
  // NOTE (freelist-flush-invariant campaign): a push offered while `io.flush` is
  // high is SILENTLY DROPPED here — `io.push(k).valid` is simply never sampled in
  // this scope, so a legitimate commit's freed old-pdst would never return to the
  // pool (a permanent physreg leak) if it ever coincided with a flush cycle. This
  // component alone cannot enforce "commits never land on a flush cycle" (it only
  // sees `io.push`/`io.flush` as opaque IO, with no notion of "legitimate commit"),
  // so that invariant is owned and enforced upstream, in RobPlugin: every producer
  // of the `RenameCommitService.commitPorts` port that eventually drives `io.push`
  // here is gated through `headReady`, which ANDs in `!flushing` directly — making
  // `commitPorts(k).valid && flushing` architecturally unreachable by construction
  // (not merely by retire-ordering convention). See RobPlugin.scala's `headReady`
  // definition and the `GenerationFlags.simulation { assert(...) }` right after
  // `rc.flushPort := flushing`, which pins that guarantee so a future commit path
  // added upstream that bypasses `headReady` trips loudly instead of leaking here.
  when(initDone && !io.flush) {
    val takeCount = io.pop.map(p => p.take.asUInt.resize(log2Up(popPorts + 1))).reduceLeft(_ + _)
    head     := (head + takeCount.resized).resized
    commHead := (commHead + commitCount.resized).resized
    count    := (count - takeCount.resized + reclaimCount.resized).resized
  }

  // Drain every cycle, even during flush. Equal input/output lane counts mean
  // no queue-full state or commit backpressure. Valid bits reset to empty.
  when(initDone) {

    // Writes are COMPACTED: the v-th valid push lands at tail + v (mirrors the pop
    // side), so a sparse push (only port 1 valid) still packs at tail.
    for (j <- 0 until pushPorts) {
      val lowerValids =
        if (j == 0) U(0, pcW bits)
        else (0 until j).map(i => reclaim(i).valid.asUInt.resize(pcW)).reduce(_ +^ _).resize(pcW)
      dbgPushAddr(j) := (tail + lowerValids.resized).resized
      // COMPLETE explicit enable (see the init-write gotcha note above): the when-scope
      // condition must be repeated here — Mem.write's explicit enable ignores the scope.
      ram.write(
        address = dbgPushAddr(j),
        data    = reclaim(j).payload,
        enable  = initDone && reclaim(j).valid
      )
    }

    tail := (tail + reclaimCount.resized).resized
  }
}
