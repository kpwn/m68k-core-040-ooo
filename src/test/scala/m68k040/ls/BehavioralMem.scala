package m68k040.ls

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.amba4.axi.sim.Axi4ReadOnlySlaveAgent
import spinal.lib.sim.{SparseMemory, StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable

/** Behavioral 128-bit read/write AXI memory for the D-side (L1D refill reads +
  * store-queue write-through). Mirrors the I-cache sim memory (Axi4ReadOnlySlaveAgent
  * over a SparseMemory) and extends it with a write path that applies per-byte strobes
  * into the same SparseMemory.
  *
  * Backing store is a SparseMemory (byte addressable, big number space). The agents
  * share one image so a write-through is observable by a later refill read. */
object BehavioralMem {
  val DATA_BITS  = 128
  val BYTES      = DATA_BITS / 8

  /** AXI config used by the L1D data side and the SQ-drain. 128-bit data = one
    * 16-byte line per beat. */
  def axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth    = DATA_BITS,
    idWidth      = 4,
    useId        = true,
    useRegion    = false,
    useBurst     = true,
    useLock      = false,
    useCache     = false,
    useSize      = true,
    useQos       = false,
    useLen       = true,
    useLast      = true,
    useResp      = true,
    useProt      = false,
    useStrb      = true
  )
}

/** Attaches read + write slave agents to a full Axi4 bus, both backed by the same
  * SparseMemory. Exposes pokeByte/peekByte helpers for the testbench (mirrors the
  * I-cache sim's preloaded-image accessors). */
class BehavioralMemAgent(axi: Axi4, cd: ClockDomain, sharedMem: SparseMemory = null) {
  // Optionally SHARE a backing SparseMemory with another agent (e.g. the D-cache and
  // the MMU walker both view the same physical memory — the page table the handler
  // writes via the D-cache must be visible to the walker). Default: own memory.
  val mem = if (sharedMem != null) sharedMem else SparseMemory()

  // ---- read path: serve bytes from the shared image ----
  val readAgent = new Axi4ReadOnlySlaveAgent(axi.ar, axi.r, cd) {
    override def readByte(address: BigInt, id: Int): Byte = mem.read(address.toLong)
  }

  // ---- write path: WRITE-BEFORE-ACK AXI-slave contract --------------------------
  // A correct AXI slave applies the write bytes to memory BEFORE asserting the B
  // (write-response). The DUT relies on this: it pops a store-queue entry on the
  // AXI-B ack (forwarding until then), and a younger load that MISSES L1D refills
  // straight from this memory via the read agent. If the bytes were applied AFTER
  // (or in a callback decoupled from) B, a refill read landing between B and the
  // apply would observe STALE memory — a harness-induced store->load race that is
  // not a real-hardware behavior.
  //
  // We therefore do NOT use the stock Axi4WriteOnlySlaveAgent (whose b response is
  // driven by an independent StreamDriver, decoupled from the separate w-monitor
  // that applied the bytes). Instead this self-contained agent buffers the aw/w
  // beats, APPLIES the per-byte strobes to `mem` the moment a w-beat is paired with
  // its aw (i.e. as the w handshake is consumed), and only THEN enqueues the burst's
  // B-response closure into a b-queue driven by our own StreamDriver(b). Because the
  // bytes are applied strictly before the burst's B closure is ever dequeued/driven,
  // the invariant holds: once the DUT observes B for a write, any later refill read
  // of that address returns the written bytes.
  private val busConfig = axi.config

  private case class AwState(addr: BigInt, size: Int, len: Int, burst: Int, id: Int, var beat: Int)
  private val awQueue = mutable.Queue[AwState]()
  private val wQueue  = mutable.Queue[(BigInt, BigInt, Boolean)]() // (data, strb, last)

  // Per-id queues of B-response closures (each sets b.id/b.resp). A closure is
  // enqueued ONLY after every byte of its burst has been written to `mem`.
  private val idCount = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val bQueue  = Array.fill(idCount)(mutable.Queue[() => Unit]())
  private var qPending = 0
  private val bQueueDepth = 4

  private def applyBeat(st: AwState, data: BigInt, strb: BigInt): Unit = {
    val bpb  = 1 << st.size
    val base = st.burst match {
      case 1 => st.addr + BigInt(bpb) * st.beat // INCR
      case _ => st.addr                          // FIXED
    }
    for (i <- 0 until BehavioralMem.BYTES) {
      if (((strb >> i) & 1) == 1) {
        val byte = ((data >> (8 * i)) & 0xff).toInt.toByte
        mem.write((base + i).toLong, byte)
      }
    }
    st.beat += 1
  }

  // Pair buffered aw + w beats in arrival order. Each paired w-beat is APPLIED to
  // `mem` immediately; on the LAST beat of a burst we enqueue that burst's B closure
  // (so the bytes are guaranteed visible before B can be driven).
  private def update(): Unit = {
    while (awQueue.nonEmpty && wQueue.nonEmpty) {
      val st = awQueue.head
      val (data, strb, _) = wQueue.dequeue()
      val isLast = st.beat == st.len
      applyBeat(st, data, strb)
      if (isLast) {
        awQueue.dequeue()
        val id = st.id
        qPending += 1
        bQueue(id) += { () =>
          if (busConfig.useId) axi.b.id #= id
          if (busConfig.useResp) axi.b.resp #= 0
        }
      }
    }
  }

  val awMonitor = StreamMonitor(axi.aw, cd) { aw =>
    val id    = if (busConfig.useId) aw.id.toInt else 0
    val size  = if (busConfig.useSize) aw.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) aw.len.toInt else 0
    val burst = if (busConfig.useBurst) aw.burst.toInt else 1
    awQueue += AwState(aw.addr.toBigInt, size, len, burst, id, 0)
    update()
  }

  val wMonitor = StreamMonitor(axi.w, cd) { w =>
    val last = if (busConfig.useLast) w.last.toBoolean else false
    wQueue += ((w.data.toBigInt, w.strb.toBigInt, last))
    update()
  }

  // Drive the B response from the per-id closure queues. By the time a closure is
  // present here, its burst's bytes are already in `mem` (applied in update()).
  val bDriver = StreamDriver(axi.b, cd) { _ =>
    val queues = bQueue.filter(_.nonEmpty)
    if (queues.nonEmpty) {
      queues(simRandom.nextInt(queues.size)).dequeue().apply()
      qPending -= 1
      true
    } else false
  }

  // Accept aw/w beats (ready randomizers), bounded by the outstanding-B depth so we
  // never run unboundedly ahead of the B responses (mirrors the stock agent).
  val awDriver = StreamReadyRandomizer(axi.aw, cd, () => qPending < bQueueDepth)
  val wDriver  = StreamReadyRandomizer(axi.w, cd, () => qPending < bQueueDepth)

  // ---- testbench helpers ----
  def pokeByte(addr: Long, value: Int): Unit = mem.write(addr, value.toByte)
  def peekByte(addr: Long): Int             = mem.read(addr).toInt & 0xff

  /** Preload a 128-bit word (little-endian byte i = data>>>(8*i)) at a 16-aligned addr. */
  def poke128(addr: Long, data: BigInt): Unit =
    for (i <- 0 until BehavioralMem.BYTES) pokeByte(addr + i, ((data >> (8 * i)) & 0xff).toInt)

  def peek128(addr: Long): BigInt =
    (0 until BehavioralMem.BYTES).foldLeft(BigInt(0)) { (acc, i) =>
      acc | (BigInt(peekByte(addr + i)) << (8 * i))
    }
}
