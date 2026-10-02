package m68k040.mmu

import spinal.core._
import spinal.core.sim._
import spinal.lib._

case class UmWriteAlloc() extends Bundle {
  val robId   = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val addr    = UInt(32 bits)   // byte address of the descriptor byte to RMW
  val newByte = Bits(8 bits)    // new value of that byte (old | set-bits)
  /** BORN COMMITTED -- for a producer that has NO owning robId at all.
    *
    * The commit mechanism below marks an entry architectural when the ROB retires
    * the robId it was tagged with. That needs the write to HAVE an owning
    * instruction. Two producers in this core do not:
    *
    *   * an INSTRUCTION FETCH walk (`ItlbPlugin`). The I-side translation request
    *     carries {vpn, supervisor, write} and nothing else -- a fetch happens
    *     before rename, so no robId exists yet. It was hardwired to 0, i.e. the
    *     write became architectural whenever whatever instruction happened to hold
    *     robId 0 retired: an arbitrary time, under an unrelated identity.
    *   * an EXCEPTION-episode walk (`ExceptionUnit` through the D side), tagged
    *     with `lsEu.xlateRobId` -- whatever robId the squashed LS pipe last held.
    *
    * `preCommitted` says "this producer has no owner; do not wait for one". The
    * entry is committed at allocation, drains in order through the same RMW path,
    * and is immune to flush (the flush block below keeps committed entries), which
    * is precisely the right lifetime for a write that no instruction owns.
    *
    * It does NOT weaken the speculation gating: the caller still refuses to
    * allocate at all for a walk that was poisoned mid-flight (`walkUmPoison` /
    * `walkFlushPoison` / `flushAll`). What changes is only WHEN a clean walk's
    * update becomes architectural -- deterministically, instead of on a borrowed
    * identity's retirement. */
  val preCommitted = Bool()
}

/** A drained U/M descriptor write request: the queue presents the oldest committed
  * entry's {addr, newByte} for the owner to perform via the descriptor-write path
  * (a single-byte RMW). Held until `drainAck`. */
case class UmWriteDrain() extends Bundle {
  val addr    = UInt(32 bits)
  val newByte = Bits(8 bits)
}

/** Deferred 68040 U/M descriptor-write queue.
  *
  * A table walk that sets the page descriptor's U bit (and M on a write access)
  * produces an architectural memory write. Those are NOT performed speculatively:
  * each is QUEUED here tagged with the triggering instruction's robId, and:
  *   - commit (ROB retired that robId) -> mark the entry committed
  *   - drain  : the OLDEST COMMITTED entry drives a single-byte descriptor write,
  *              held until `drainAck`, then vacates its slot (commit order)
  *   - flush  : speculative (uncommitted) entries are discarded -> never written
  * Four independently valid slots avoid head-of-line deadlock when a younger
  * walk allocated first but an older ROB owner committed first. */
class UmWriteQueue(depth: Int = 4, retireWidth: Int = 2) extends Component {
  require(depth >= 2 && isPow2(depth))
  require(Set(2, 4, 8, 16)(retireWidth))
  val ptrW = log2Up(depth)
  private val seqW = log2Up(2 * depth + 1)

  val io = new Bundle {
    val alloc    = slave(Flow(UmWriteAlloc()))
    val commit   = slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
    // Slot-1 retire commit (a tagged op can dual-retire at h1 behind a long-latency
    // head; both retire slots must mark the SAME cycle — see StoreQueue.commitB).
    val commitB  = slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
    val commitExtra = if(retireWidth > 2)
      Vec.fill(retireWidth - 2)(slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))) else null
    val flush    = in Bool ()
    val drain    = master(Flow(UmWriteDrain()))
    val drainAck = in Bool ()
    // Admission credit for the owning single walker. A full queue must never
    // overwrite a live architectural U/M update.
    val full     = out Bool ()
    // (2026-09-09: the task #210 `pageQuery`/`pageHazard` interlock that used to live
    // here has been REMOVED. See the "WHY THERE IS NO SAME-PAGE INTERLOCK" note below
    // the port bundle for the full argument -- it compared two different address
    // spaces, could not fire in the case it claimed to cover, and could not be made
    // to fire correctly without introducing a deadlock.)
  }

  // ══ WHY THERE IS NO SAME-PAGE INTERLOCK (2026-09-09) ══════════════════════════
  // Task #210 shipped a `pageQuery`/`pageHazard` pair here, driven by DtlbPlugin from
  // `_req.payload.vpn` and compared against `addrs(i)(31 downto 12)`. Those are two
  // DIFFERENT ADDRESS SPACES: the query was a VIRTUAL page number, the entry holds the
  // PHYSICAL byte address of a page-table descriptor. The compare is only meaningful
  // when the map happens to be identity, so the interlock is DEAD under any
  // non-identity supervisor map -- and its own comment claimed the opposite ("only ever
  // over-blocks, never under-blocks"). A dead interlock that reads as live is worse
  // than none, so it is gone. It is replaced by an argument, not by a stronger check:
  //
  // (a) THE WALK CASE IS PROVABLY HARMLESS. U and M are MONOTONIC: every walk computes
  //     `newByte = curByte | U | (M if write)` (TableWalker RD_PAGE) and hardware never
  //     clears either bit. If a later walk re-reads a descriptor whose queued update has
  //     not landed yet, it reads the stale byte and recomputes the SAME set-bits, so it
  //     queues an IDEMPOTENT duplicate write. Nothing is lost and nothing is wrong. The
  //     `monotonicity tripwire` below keeps that claim honest in simulation.
  //     The common path does not even re-walk: the triggering walk filled the ATC entry
  //     with `WalkRsp.modified` (already the POST-update value, see MmuTypes), so a
  //     program-order-later access to the same page HITS and never consults memory.
  //
  // (b) THE REMAINING CASE CANNOT BE EXPRESSED AT REQUEST TIME. The one real difference
  //     from silicon is software READING a descriptor's low byte as ordinary data while
  //     that byte's deferred write is still queued (on a real 68040 the descriptor write
  //     is part of the faulting access's own bus activity, so it has already landed).
  //     Detecting that needs the ACCESS's PHYSICAL page -- which is not known until the
  //     access has been translated, i.e. strictly after the point any request-side
  //     interlock could act.
  //
  // (c) A CORRECTED INTERLOCK STILL NEEDS ROB AGE. Entries are allocated in WALK
  //     COMPLETION order, which is LS ISSUE order (IssueQueuePlugin selects the oldest
  //     READY op, not the oldest op), not program order. This queue now drains an
  //     eligible committed entry past an uncommitted younger predecessor, removing
  //     the old head-of-line blocker. A physical-byte interlock must nevertheless
  //     distinguish older from younger entries: waiting on ANY matching speculative
  //     entry can still deadlock against an instruction that cannot yet retire.
  //
  // The DTLB/ITLB drain now re-reads and ORs only U/M rather than replaying a
  // sampled whole byte. Cross-queue age ordering against an ordinary software
  // descriptor store is still a separate architectural requirement: this queue
  // alone cannot tell whether that SQ store should precede its metadata write.
  val valids    = Vec.fill(depth)(RegInit(False))
  val committed = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)))
  val addrs     = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val bytes     = Vec.fill(depth)(RegInit(B(0, 8 bits)))
  // Commit order, not walk-allocation order, is the architectural order. Only
  // notices matching NEWLY committed live entries advance nextSeq: arbitrarily
  // many unrelated ROB commits cannot age a stalled entry. While an offer is
  // held, at most depth-1 OTHER entries can enter this four-slot queue, so
  // surviving stamps are at most depth apart. seqW's modulus is strictly
  // greater than 2*depth, so subtract-and-compare is unambiguous across wrap.
  // Two entries of one owner stamped on the same edge tie and use physical
  // slot index as the deterministic order. A born-committed allocation in a
  // multi-lane retire cycle is stamped AFTER those retirement lanes.
  val commitSeq = Vec.fill(depth)(Reg(UInt(seqW bits)) init 0)
  val nextSeq   = Reg(UInt(seqW bits)) init 0
  nextSeq.simPublic()
  io.full := valids.asBits.andR
  val freeSlot = OHToUInt(OHMasking.first(~valids.asBits))

  // ---- commit: stamp matching entries in architectural retirement order ----
  val commitNotices = Seq(io.commit, io.commitB) ++
    (if(retireWidth > 2) io.commitExtra.toSeq else Seq.empty)
  val laneCreates = commitNotices.zipWithIndex.map { case (c, lane) =>
    val duplicate = commitNotices.take(lane).map(e => e.valid &&
      (e.payload === c.payload)).foldLeft(False: Bool)(_ || _)
    c.valid && !duplicate && (0 until depth).map(i => valids(i) &&
      !committed(i) && (robIds(i) === c.payload)).reduce(_ || _)
  }
  val commitCount = CountOne(B(laneCreates))
  for (i <- 0 until depth) {
    for (lane <- commitNotices.indices) {
      val c = commitNotices(lane)
      val earlier = commitNotices.take(lane).map(e => e.valid &&
        (e.payload === robIds(i))).foldLeft(False: Bool)(_ || _)
      when(valids(i) && !committed(i) && c.valid &&
           (c.payload === robIds(i)) && !earlier) {
        committed(i) := True
        val offset = if (lane == 0) U(0, seqW bits)
                     else CountOne(B(laneCreates.take(lane))).resize(seqW bits)
        commitSeq(i) := nextSeq + offset
      }
    }
  }
  val bornCommitted = io.alloc.valid && !io.flush && !io.full && io.alloc.payload.preCommitted
  nextSeq := nextSeq + commitCount.resize(seqW bits) + bornCommitted.asUInt.resize(seqW bits)

  // ---- alloc: use any free slot; no ring hole can block an older drain ----
  when(io.alloc.valid && !io.flush) {
    assert(!io.full, "UmWriteQueue allocation attempted while full")
  }
  when(io.alloc.valid && !io.flush && !io.full) {
    valids(freeSlot)    := True
    committed(freeSlot) := io.alloc.payload.preCommitted
    commitSeq(freeSlot) := nextSeq + commitCount.resize(seqW bits)
    robIds(freeSlot)    := io.alloc.payload.robId
    addrs(freeSlot)     := io.alloc.payload.addr
    bytes(freeSlot)     := io.alloc.payload.newByte
  }

  // A flush discards only truly speculative entries. A simultaneous retirement
  // notice is treated as architectural, even before its register updates.
  when(io.flush) {
    for (i <- 0 until depth) {
      val committingNow = commitNotices.map(c => c.valid &&
        (c.payload === robIds(i))).reduce(_ || _)
      when(valids(i) && !committed(i) && !committingNow) { valids(i) := False }
    }
  }

  // ---- drain: oldest committed stamp, held stable until terminal ack ----
  var bestValid: Bool = False
  var bestSlot: UInt = U(0, ptrW bits)
  var bestAge: UInt = U(0, seqW bits)
  for (i <- 0 until depth) {
    val eligible = valids(i) && committed(i)
    val age = nextSeq - commitSeq(i)
    val choose = eligible && (!bestValid || (age > bestAge))
    bestSlot = Mux(choose, U(i, ptrW bits), bestSlot)
    bestAge = Mux(choose, age, bestAge)
    bestValid = bestValid || eligible
  }
  val drainBusy = RegInit(False)
  val drainSlot = Reg(UInt(ptrW bits)) init 0
  val offerSlot = Mux(drainBusy, drainSlot, bestSlot)
  // Retain the pre-existing one-cycle valid withdrawal on flush; the latched
  // offer slot and payload survive it and are re-offered unchanged next cycle.
  io.drain.valid := (drainBusy || bestValid) && !io.flush
  io.drain.payload.addr := addrs(offerSlot)
  io.drain.payload.newByte := bytes(offerSlot)
  when(io.drain.valid && !drainBusy) {
    drainBusy := True
    drainSlot := offerSlot
  }
  when(io.drainAck && drainBusy) {
    drainBusy := False
    valids(drainSlot) := False
  }
  GenerationFlags.simulation {
    assert(!(io.drainAck && !drainBusy),
      "UmWriteQueue: drain ack without an offered committed entry", FAILURE)
    when(drainBusy) {
      assert(valids(drainSlot) && committed(drainSlot),
        "UmWriteQueue: held drain slot lost its committed identity", FAILURE)
    }
    for (i <- 0 until depth) when(valids(i) && committed(i)) {
      assert((nextSeq - commitSeq(i)) <= U(depth, seqW bits),
        "UmWriteQueue: commit sequence span exceeded live-slot capacity", FAILURE)
    }
  }

  // ── monotonicity tripwire (sim-only) ──────────────────────────────────────────
  // The "no same-page interlock is needed" argument at the top of this file rests on
  // U/M updates being MONOTONIC: a second walk of a descriptor whose queued write has
  // not landed yet must recompute a byte that is a SUPERSET of the one already queued,
  // so the duplicate is idempotent. Assert exactly that, rather than trusting it.
  GenerationFlags.simulation {
    when(io.alloc.valid && !io.flush) {
      for (i <- 0 until depth) {
        when(valids(i) && (addrs(i) === io.alloc.payload.addr)) {
          assert((io.alloc.payload.newByte & bytes(i)) === bytes(i),
            "UmWriteQueue: a newly queued U/M descriptor byte CLEARS a bit an already-queued " +
            "write to the same address had set -- the monotonicity the removed same-page " +
            "interlock's absence relies on is broken",
            FAILURE)
        }
      }
    }
  }
}
