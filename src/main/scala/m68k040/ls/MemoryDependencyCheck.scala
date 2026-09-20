package m68k040.ls

import spinal.core._

/** One physical 16-byte fragment. A zero mask denotes an absent fragment. */
case class MemoryByteFragment() extends Bundle {
  val line = UInt(28 bits)
  val mask = Bits(16 bits)
}

/** Snapshot supplied by the memory-order table, not by the issue queue.
  * `older` must include committed stores whose ROB IDs may already be reused.
  * Address-known includes translation AND final serialization attributes.
  */
case class MemoryDependencyRecord() extends Bundle {
  val older = Bool()
  val addressKnown = Bool()
  val store = Bool()
  val serial = Bool()
  val dataReady = Bool()
  val fragments = Vec(MemoryByteFragment(), 2)
}

/** Conservative, non-speculative permission to read memory (NOT to forward).
  * An overlap always blocks this path, even if the store's data is ready.
  * Forwarding is a separate result source and must never accidentally enable
  * a stale cache read. Caller registers the decision before issuing to cache.
  */
class MemoryDependencyCheck(entries: Int) extends Component {
  require(entries > 0)
  val io = new Bundle {
    val valid = in Bool()
    val addressKnown = in Bool()
    val serial = in Bool()
    val atCommit = in Bool()
    val fragments = in(Vec(MemoryByteFragment(), 2))
    val records = in(Vec(MemoryDependencyRecord(), entries))
    val allowMemory = out Bool()
    val unknown = out Bits(entries bits)
    val barrier = out Bits(entries bits)
    val overlap = out Bits(entries bits)
    val waitingData = out Bits(entries bits)
  }
  val active = io.valid && io.addressKnown && io.fragments.map(_.mask.orR).reduce(_ || _)
  for (n <- 0 until entries) {
    val r = io.records(n)
    val hit = (for (load <- io.fragments; store <- r.fragments) yield
      (load.line === store.line) && (load.mask & store.mask).orR).reduce(_ || _)
    io.unknown(n) := io.valid && r.older && !r.addressKnown
    io.barrier(n) := io.valid && r.older && (r.serial || io.serial)
    io.overlap(n) := active && r.older && r.addressKnown && r.store && hit
    io.waitingData(n) := io.overlap(n) && !r.dataReady
  }
  io.allowMemory := active && (!io.serial || io.atCommit) &&
    !(io.unknown.orR || io.barrier.orR || io.overlap.orR)
}
