package m68k040.ls

import spinal.core._
import spinal.lib._

case class MemoryOrderTicket(slotWidth: Int) extends Bundle {
  val slot = UInt(slotWidth bits)
  val generation = UInt(32 bits)
}
case class MemoryOrderReservation() extends Bundle {
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val store = Bool()
  val serial = Bool()
}
case class MemoryOrderAddress(slotWidth: Int) extends Bundle {
  val ticket = MemoryOrderTicket(slotWidth)
  val serial = Bool()
  val fragments = Vec(MemoryByteFragment(), 2)
}

/** Dispatch-allocated ordering metadata; store bytes remain owned by the LSU/SQ.
  * Canceled slots remain occupied until the client releases them AFTER draining
  * every outstanding response. Generation checks are additional protection,
  * not permission to reuse a slot with live transactions.
  *
  * Committed stores and irrevocable operations survive flush. The client must
  * serialize committing/starting irreversible work against the ROB's flush.
  * No selection/retry or CPU integration is implied by this standalone block.
  */
class MemoryDependencyTracker(entries: Int = 8) extends Component {
  require(entries >= 2 && isPow2(entries))
  val slotWidth = log2Up(entries)
  val io = new Bundle {
    val reserve = slave(Stream(Vec(MemoryOrderReservation(), 2)))
    val reserveSecond = in Bool()
    val tickets = out(Vec(MemoryOrderTicket(slotWidth), 2))
    val address = slave(Flow(MemoryOrderAddress(slotWidth)))
    val dataReady = slave(Flow(MemoryOrderTicket(slotWidth)))
    val commit = Vec.fill(2)(slave(Flow(MemoryOrderTicket(slotWidth))))
    val irreversible = slave(Flow(MemoryOrderTicket(slotWidth)))
    // Release is an assertion by the owner that no response/use can remain.
    val release = Vec.fill(2)(slave(Flow(MemoryOrderTicket(slotWidth))))
    val flush = in Bool()
    val query = slave(Flow(MemoryOrderTicket(slotWidth)))
    val atCommit = in Bool()
    val allowMemory = out Bool()
    val queryPresent = out Bool()
    val occupied = out Bits(entries bits)
    val canceled = out Bits(entries bits)
    val unknown = out Bits(entries bits)
    val barrier = out Bits(entries bits)
    val overlap = out Bits(entries bits)
    val waitingData = out Bits(entries bits)
  }
  val occupied = Reg(Bits(entries bits)) init 0
  val canceled = Reg(Bits(entries bits)) init 0
  val committed = Reg(Bits(entries bits)) init 0
  val irreversible = Reg(Bits(entries bits)) init 0
  val known = Reg(Bits(entries bits)) init 0
  val dataReady = Reg(Bits(entries bits)) init 0
  val stores = Reg(Bits(entries bits)) init 0
  val serial = Reg(Bits(entries bits)) init 0
  val generation = Vec.fill(entries)(Reg(UInt(32 bits)) init 0)
  val fragments = Vec.fill(entries)(Reg(Vec(MemoryByteFragment(), 2)))
  io.occupied := occupied
  io.canceled := occupied & canceled

  def matches(t: MemoryOrderTicket, n: Int): Bool =
    occupied(n) && t.slot === n && t.generation === generation(n)

  val free = ~occupied
  val first = OHMasking.first(free)
  val second = OHMasking.first(free & ~first)
  val selected = Seq(first, second)
  io.reserve.ready := first.orR && (!io.reserveSecond || second.orR) && !io.flush
  val allocating = (first | second.andMask(io.reserveSecond)).andMask(io.reserve.fire)
  // Stable allocation order, one bit per unordered slot pair. No dependence on
  // the live ROB head, wrapping sequence arithmetic or the lifetime of a peer.
  // A new allocation is younger than every survivor; lane 0 precedes lane 1.
  val precedes = Vec.fill(entries)(Vec(Bool(), entries))
  for (i <- 0 until entries) {
    precedes(i)(i) := False
    for (j <- i + 1 until entries) {
      val iBeforeJ = RegInit(False)
      when(allocating(i) || allocating(j)) {
        iBeforeJ := allocating(j) && (!allocating(i) || first(i))
      }
      precedes(i)(j) := iBeforeJ
      precedes(j)(i) := !iBeforeJ
    }
  }
  for (lane <- 0 until 2) {
    io.tickets(lane).slot := OHToUInt(selected(lane)).resized
    io.tickets(lane).generation := generation(io.tickets(lane).slot) + 1
  }
  for (n <- 0 until entries) {
    when(io.address.valid && matches(io.address.ticket, n) && !canceled(n)) {
      known(n) := True
      serial(n) := serial(n) || io.address.serial
      fragments(n) := io.address.fragments
    }
    when(io.dataReady.valid && matches(io.dataReady.payload, n) && !canceled(n)) {
      dataReady(n) := True
    }
    val committing = io.commit.map(c => c.valid && matches(c.payload, n)).reduce(_ || _)
    val launching = io.irreversible.valid && matches(io.irreversible.payload, n)
    when(committing && !canceled(n)) { committed(n) := True }
    when(launching && !canceled(n)) { irreversible(n) := True }
    // Same-edge commit/irreversible notices preserve an existing operation.
    when(io.flush && occupied(n) && !committed(n) && !irreversible(n) && !committing && !launching) {
      canceled(n) := True
    }
    for (r <- io.release) when(r.valid && matches(r.payload, n)) {
      occupied(n) := False; canceled(n) := False
    }
    for (lane <- 0 until 2) {
      when(io.reserve.fire && selected(lane)(n) && (if(lane == 0) True else io.reserveSecond)) {
        occupied(n) := True; canceled(n) := False
        committed(n) := False; irreversible(n) := False
        known(n) := False; dataReady(n) := False
        stores(n) := io.reserve.payload(lane).store
        serial(n) := io.reserve.payload(lane).serial
        generation(n) := generation(n) + 1
      }
    }
  }

  val q = io.query.payload.slot
  val present = io.query.valid && occupied(q) && !canceled(q) &&
    io.query.payload.generation === generation(q) && !io.flush
  io.queryPresent := present
  val checker = new MemoryDependencyCheck(entries)
  checker.setName("dependencyCheck")
  checker.io.valid := present && !stores(q)
  checker.io.addressKnown := known(q)
  checker.io.serial := serial(q)
  checker.io.atCommit := io.atCommit
  checker.io.fragments := fragments(q)
  for (n <- 0 until entries) {
    val r = checker.io.records(n)
    // A committed or irrevocably launched operation predates speculative loads,
    // including a load reusing that store's old ROB ID after circular wrap.
    r.older := occupied(n) && !canceled(n) && q =/= n &&
      (committed(n) || irreversible(n) || precedes(n)(q))
    r.addressKnown := known(n)
    r.store := stores(n)
    r.serial := serial(n)
    r.dataReady := dataReady(n)
    r.fragments := fragments(n)
  }
  io.allowMemory := checker.io.allowMemory
  io.unknown := checker.io.unknown
  io.barrier := checker.io.barrier
  io.overlap := checker.io.overlap
  io.waitingData := checker.io.waitingData
}
