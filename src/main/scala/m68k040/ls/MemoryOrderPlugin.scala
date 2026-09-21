package m68k040.ls

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Shared wires, not storage. Dispatch owns reserve/reserveSecond; the LSU
  * integration owns address/data/commit/irreversible/release/flush/query.
  * MemoryOrderPlugin alone produces readiness, tickets and query results. */
case class MemoryOrderPorts(entries: Int) extends Bundle {
  val slotWidth = log2Up(entries)
  val reserve = Stream(Vec(MemoryOrderReservation(), 2))
  val reserveSecond = Bool()
  val tickets = Vec(MemoryOrderTicket(slotWidth), 2)
  val address = Flow(MemoryOrderAddress(slotWidth))
  val dataReady = Flow(MemoryOrderTicket(slotWidth))
  val commit = Vec.fill(2)(Flow(MemoryOrderTicket(slotWidth)))
  val irreversible = Flow(MemoryOrderTicket(slotWidth))
  val release = Vec.fill(2)(Flow(MemoryOrderTicket(slotWidth)))
  val flush = Bool()
  val query = Flow(MemoryOrderTicket(slotWidth))
  val atCommit = Bool()
  val allowMemory = Bool()
  val queryPresent = Bool()
  val occupied = Bits(entries bits)
  val canceled = Bits(entries bits)
  val unknown = Bits(entries bits)
  val barrier = Bits(entries bits)
  val overlap = Bits(entries bits)
  val waitingData = Bits(entries bits)
}

/** Sole producer/owner: MemoryOrderPlugin. No Global database key is required. */
trait MemoryOrderService {
  def memoryOrder: MemoryOrderPorts
}

/** Optional dispatch-to-LSU ownership boundary. Do not install in a full CPU
  * until every lifecycle channel has an owner; reservations alone cannot grant
  * out-of-order memory permission. Ports are allocated in setup so consumers
  * do not create a dispatch -> table -> LSU -> dispatch build dependency. */
class MemoryOrderPlugin(entries: Int = 8) extends FiberPlugin with MemoryOrderService {
  require(entries >= 2 && isPow2(entries))
  private var ports: MemoryOrderPorts = null
  during setup { ports = MemoryOrderPorts(entries) }
  override def memoryOrder: MemoryOrderPorts = ports
  val logic = during build new Area {
    val table = new MemoryDependencyTracker(entries)
    table.io.reserve << ports.reserve
    table.io.reserveSecond := ports.reserveSecond
    ports.tickets := table.io.tickets
    table.io.address << ports.address
    table.io.dataReady << ports.dataReady
    for (n <- 0 until 2) {
      table.io.commit(n) << ports.commit(n)
      table.io.release(n) << ports.release(n)
    }
    table.io.irreversible << ports.irreversible
    table.io.flush := ports.flush
    table.io.query << ports.query
    table.io.atCommit := ports.atCommit
    ports.allowMemory := table.io.allowMemory
    ports.queryPresent := table.io.queryPresent
    ports.occupied := table.io.occupied
    ports.canceled := table.io.canceled
    ports.unknown := table.io.unknown
    ports.barrier := table.io.barrier
    ports.overlap := table.io.overlap
    ports.waitingData := table.io.waitingData
  }
}
