package m68k040.ls

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.amba4.axi.sim.{Axi4ReadOnlySlaveAgent, Axi4WriteOnlySlaveAgent}
import spinal.lib.sim.{SparseMemory, StreamMonitor}

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

  // ---- write path: the stock agent drives aw/w/b handshakes and the b response,
  // but does NOT write the image. We add an independent monitor that reconstructs
  // each burst's beat addresses and applies the per-byte strobes ourselves. ----
  val writeAgent = new Axi4WriteOnlySlaveAgent(axi.aw, axi.w, axi.b, cd)

  // The aw and w channels are independent streams; beats can arrive in any
  // interleaving. Buffer both and pair them up in arrival order in update().
  private case class AwState(addr: BigInt, size: Int, len: Int, burst: Int, var beat: Int)
  private case class WBeat(data: BigInt, strb: BigInt)
  private val awQueue = mutable.Queue[AwState]()
  private val wQueue  = mutable.Queue[WBeat]()

  private def applyBeat(st: AwState, beat: WBeat): Unit = {
    val bpb  = 1 << st.size
    val base = st.burst match {
      case 1 => st.addr + BigInt(bpb) * st.beat // INCR
      case _ => st.addr                          // FIXED
    }
    for (i <- 0 until BehavioralMem.BYTES) {
      if (((beat.strb >> i) & 1) == 1) {
        val byte = ((beat.data >> (8 * i)) & 0xff).toInt.toByte
        mem.write((base + i).toLong, byte)
      }
    }
    st.beat += 1
  }

  private def update(): Unit = {
    while (awQueue.nonEmpty && wQueue.nonEmpty) {
      val st = awQueue.head
      applyBeat(st, wQueue.dequeue())
      if (st.beat > st.len) awQueue.dequeue()
    }
  }

  StreamMonitor(axi.aw, cd) { aw =>
    awQueue += AwState(aw.addr.toBigInt, aw.size.toInt, aw.len.toInt, aw.burst.toInt, 0)
    update()
  }

  StreamMonitor(axi.w, cd) { w =>
    wQueue += WBeat(w.data.toBigInt, w.strb.toBigInt)
    update()
  }

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
