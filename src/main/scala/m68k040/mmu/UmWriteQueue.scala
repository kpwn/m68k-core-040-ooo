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
    // Task #210: page-granularity hazard check against every still-VALID (not yet
    // drained/acked) entry, regardless of its committed state. A queued-but-
    // undrained deferred descriptor write is invisible to ordinary memory reads
    // until the AXI RMW actually lands -- strictly LATER than when the triggering
    // instruction's own translation resolves (which only requires the WALK to
    // finish, not the drain). The owning DTLB uses this to hold off translating a
    // program-order-later access to the SAME PAGE until the write has drained, so
    // it cannot observe the stale pre-update descriptor byte. Page-granularity
    // (not exact-byte) is a deliberately conservative/cheap over-approximation:
    // it only ever adds a rare, correctly-scoped stall, never misses a real
    // hazard.
    val pageQuery  = in UInt (20 bits)
    val pageHazard = out Bool ()
  }

  val valids    = Vec.fill(depth)(RegInit(False))
  val committed = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, 6 bits)))
  val addrs     = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val bytes     = Vec.fill(depth)(RegInit(B(0, 8 bits)))

  val head = RegInit(U(0, ptrW bits))
  val tail = RegInit(U(0, ptrW bits))
  io.full := valids.asBits.andR
  io.pageHazard := (0 until depth).map(i =>
    valids(i) && (addrs(i)(31 downto 12) === io.pageQuery)
  ).reduce(_ || _)

  // ---- drain: oldest valid+committed entry, held until ack ----
  val drainBusy  = RegInit(False)
  val headReady  = valids(head) && committed(head) && !io.flush
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
    robIds(tail)    := io.alloc.payload.robId
    addrs(tail)     := io.alloc.payload.addr
    bytes(tail)     := io.alloc.payload.newByte
    tail := tail + 1
  }

  // ---- flush: discard speculative (uncommitted); keep committed (oldest run) ----
  when(io.flush) {
    val keep = Vec(Bool(), depth)
    for (i <- 0 until depth) keep(i) := valids(i) && committed(i)
    for (i <- 0 until depth) when(!keep(i)) { valids(i) := False }
    tail := (head + CountOne(keep)).resized
  }
}
