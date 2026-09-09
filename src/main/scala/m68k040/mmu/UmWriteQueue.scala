package m68k040.mmu

import spinal.core._
import spinal.lib._

case class UmWriteAlloc() extends Bundle {
  val robId   = UInt(6 bits)
  val addr    = UInt(32 bits)   // byte address of the descriptor byte to RMW
  val newByte = Bits(8 bits)    // new value of that byte (old | set-bits)
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
  *   - drain  : the OLDEST committed entry drives a single-byte descriptor write,
  *              held until `drainAck`, then pops (in program order)
  *   - flush  : speculative (uncommitted) entries are discarded -> never written
  * Mirrors the StoreQueue commit-drain/flush discipline (all state RegInit; the
  * count is a hardware sum; flush rolls the tail back past the youngest committed). */
class UmWriteQueue(depth: Int = 4) extends Component {
  require(isPow2(depth))
  val ptrW = log2Up(depth)

  val io = new Bundle {
    val alloc    = slave(Flow(UmWriteAlloc()))
    val commit   = slave(Flow(UInt(6 bits)))
    // Slot-1 retire commit (a tagged op can dual-retire at h1 behind a long-latency
    // head; both retire slots must mark the SAME cycle — see StoreQueue.commitB).
    val commitB  = slave(Flow(UInt(6 bits)))
    val flush    = in Bool ()
    val drain    = master(Flow(UmWriteDrain()))
    val drainAck = in Bool ()
    // Admission credit for the owning single walker. A full queue must never
    // silently wrap `tail` and overwrite an older architectural U/M update.
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
  // (c) AND A CORRECTED INTERLOCK WOULD DEADLOCK. Entries are allocated in WALK
  //     COMPLETION order, which is LS ISSUE order (IssueQueuePlugin selects the oldest
  //     READY op, not the oldest op), not program order. So the queue can hold a
  //     YOUNGER instruction's entry ahead of an OLDER one's. Blocking an older access on
  //     a younger entry -- which a correct physical-page compare would do -- stalls the
  //     older access until the younger retires, and the younger cannot retire until the
  //     older does. That is a hard deadlock, and it is exactly what the domain mismatch
  //     has been hiding. Any future interlock here must first make allocation
  //     program-ordered, or drain committed entries out of order.
  //
  // Residual, unchanged by this note and NOT introduced by it: the drain is a whole-BYTE
  // RMW and that byte also carries PDT/W/CM/S. Software that rewrites a descriptor
  // between a walk's read and the drain has its change clobbered. No interlock in this
  // file ever addressed that, and a VPN-vs-PPN compare could not have.
  val valids    = Vec.fill(depth)(RegInit(False))
  val committed = Vec.fill(depth)(RegInit(False))
  /** Set by `flush` on a speculative entry that could not simply be rolled off the tail
    * (see the flush block). A dead entry keeps its slot -- so the ring stays contiguous
    * -- and pops at the head in one cycle WITHOUT issuing its descriptor write. */
  val dead      = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, 6 bits)))
  val addrs     = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val bytes     = Vec.fill(depth)(RegInit(B(0, 8 bits)))

  val head = RegInit(U(0, ptrW bits))
  val tail = RegInit(U(0, ptrW bits))
  io.full := valids.asBits.andR

  // ---- drain: oldest valid+committed entry, held until ack ----
  val drainBusy  = RegInit(False)
  /** A flush-killed entry at the head. It writes NOTHING and retires in one cycle; it
    * exists only so the ring's [head, tail) contiguity survives a flush (see below). */
  val headDead   = valids(head) && dead(head) && !drainBusy
  val headReady  = valids(head) && committed(head) && !dead(head) && !io.flush
  val drainIssue = headReady && !drainBusy
  io.drain.valid         := drainIssue
  io.drain.payload.addr    := addrs(head)
  io.drain.payload.newByte := bytes(head)

  when(drainIssue) { drainBusy := True }
  when(io.drainAck && drainBusy) {
    drainBusy    := False
    valids(head) := False
    head := head + 1
  }
  // Retire a dead entry with no bus activity at all. Cannot collide with the ack arm
  // above: `drainBusy` is only ever set for an entry that was `committed && !dead`, and
  // `headDead` is gated on `!drainBusy`.
  when(headDead) {
    valids(head) := False
    dead(head)   := False
    head := head + 1
  }

  // ---- commit: mark the matching valid entry committed ----
  when(io.commit.valid) {
    for (i <- 0 until depth)
      when(valids(i) && (robIds(i) === io.commit.payload)) { committed(i) := True }
  }
  when(io.commitB.valid) {
    for (i <- 0 until depth)
      when(valids(i) && (robIds(i) === io.commitB.payload)) { committed(i) := True }
  }

  // ---- alloc: push at tail (speculative) ----
  when(io.alloc.valid && !io.flush) {
    assert(!io.full, "UmWriteQueue allocation attempted while full")
    valids(tail)    := True
    committed(tail) := False
    dead(tail)      := False
    robIds(tail)    := io.alloc.payload.robId
    addrs(tail)     := io.alloc.payload.addr
    bytes(tail)     := io.alloc.payload.newByte
    tail := tail + 1
  }

  // ---- flush: kill speculative (uncommitted) entries, KEEP every committed one ----
  //
  // THE BUG THIS REPLACES (2026-09-09). The previous body cleared every non-kept slot
  // and then set `tail := head + CountOne(keep)` -- a POPULATION count of the surviving
  // entries, not a PREFIX count from `head`. That is only correct when the committed
  // entries form a contiguous run starting at `head`, and they need not: entries are
  // allocated in walk-COMPLETION order, which is LS ISSUE order, not program order (see
  // point (c) in the note at the top of this file), while `commit` arrives in program
  // order. So a committed entry can sit BEHIND an uncommitted one, and then:
  //   * `head` was left pointing at a slot whose `valid` had just been cleared, so
  //     `headReady` was false forever and the queue never drained again;
  //   * `tail` landed ON the surviving committed entry, which the next allocation
  //     overwrote -- silently LOSING an architectural U/M descriptor write;
  //   * the structure only healed on a later flush that happened to find nothing
  //     committed (`tail` back to `head`), so the loss was intermittent.
  // Losing an M means a page that IS dirty is later evicted as clean.
  //
  // THE FIX. `tail` is not moved and no slot is vacated out of turn, so [head, tail)
  // stays contiguous by construction. A speculative entry is instead marked DEAD: it
  // keeps its position, never issues its write, and retires at the head in a single
  // cycle (see `headDead`). Slots therefore free up one cycle later than before in the
  // common case, and correctly rather than never in the case that used to corrupt.
  when(io.flush) {
    for (i <- 0 until depth) when(valids(i) && !committed(i)) { dead(i) := True }
  }

  // ── monotonicity tripwire (sim-only) ──────────────────────────────────────────
  // The "no same-page interlock is needed" argument at the top of this file rests on
  // U/M updates being MONOTONIC: a second walk of a descriptor whose queued write has
  // not landed yet must recompute a byte that is a SUPERSET of the one already queued,
  // so the duplicate is idempotent. Assert exactly that, rather than trusting it.
  GenerationFlags.simulation {
    when(io.alloc.valid && !io.flush) {
      for (i <- 0 until depth) {
        when(valids(i) && !dead(i) && (addrs(i) === io.alloc.payload.addr)) {
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
