package m68k040.ls

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
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

  /** Task #189 (bus error): the address decode this harness models when
    * `injectBusErrors` is enabled — mirrors the REAL SoC's axi_xbar decode the
    * m68k-ooo ported-test corpus's headers describe (RAM=0x0nnn_nnnn,
    * ROM=0x4nnn_nnnn, I/O=0x5nnn_nnnn, video=0x6nnn_nnnn, sentinel=0xFFFFnnnn —
    * see exc_bus_error.s's header comment). An address outside every one of these
    * ranges is "comfortably outside every decoded prefix" (same test's wording)
    * and gets DECERR instead of a silent zero-filled OKAY. Kept intentionally
    * generous/simple (top-nibble / top-halfword checks only, no fine-grained
    * per-region size limits) — precise enough to make every unmapped probe
    * address the ported-test corpus actually uses (0xAAAA0000, 0xDEAD0000/1000/
    * 2000) fault, without clawing back any address range a legitimate test might
    * use for RAM/ROM/scratch/sentinel purposes. */
  def decoded(addr: Long): Boolean = {
    val a = addr & 0xffffffffL
    val topNibble = (a >>> 28) & 0xf
    (topNibble == 0x0L) || (topNibble == 0x4L) || (topNibble == 0x5L) || (topNibble == 0x6L) ||
      ((a >>> 16) == 0xffffL)
  }
}

/** Attaches read + write slave agents to a full Axi4 bus, both backed by the same
  * SparseMemory. Exposes pokeByte/peekByte helpers for the testbench (mirrors the
  * I-cache sim's preloaded-image accessors).
  *
  * `injectBusErrors` (task #189, default false — every PRE-EXISTING call site keeps
  * its exact old always-OKAY behavior): when true, an access to an address outside
  * `BehavioralMem.decoded` gets AXI DECERR instead of a silently-successful zero-
  * filled read / no-op-but-acked write. Opt-in per agent instance so the D-cache's
  * own memory (where PortedTestRunner enables it) can model a real bus error while
  * the ITLB/DTLB table-walker memories (a completely separate MMU concern, out of
  * this task's scope) are entirely unaffected. */
class BehavioralMemAgent(axi: Axi4, cd: ClockDomain, sharedMem: SparseMemory = null,
                          injectBusErrors: Boolean = false) {
  // Optionally SHARE a backing SparseMemory with another agent (e.g. the D-cache and
  // the MMU walker both view the same physical memory — the page table the handler
  // writes via the D-cache must be visible to the walker). Default: own memory.
  val mem = if (sharedMem != null) sharedMem else SparseMemory()

  private val busConfig = axi.config

  // ---- read path: hand-rolled (NOT the stock Axi4ReadOnlySlaveAgent) ------------
  // The stock agent has no per-access response-code hook (always OKAY) — task #189
  // needs a genuine DECERR for an undecoded address, so this mirrors the ALREADY-
  // hand-rolled write path below: buffer AR beats, emit one R-beat closure per beat
  // (computed eagerly — the DECERR/data decision only depends on `mem`/`decoded` at
  // acceptance time, never changes before it's driven) into a single in-order queue
  // (this DUT's cache/walker masters are single-outstanding in practice; no per-id
  // reordering is needed), and drive R off that queue.
  private case class ArState(addr: BigInt, size: Int, len: Int, burst: Int, id: Int)

  private def readBeatData(base: Long, bytes: Int): BigInt = {
    var v = BigInt(0)
    for (i <- 0 until bytes) v = v | (BigInt(mem.read(base + i).toInt & 0xff) << (8 * i))
    v
  }

  private val rQueue = mutable.Queue[() => Unit]()

  val arMonitor = StreamMonitor(axi.ar, cd) { ar =>
    val id    = if (busConfig.useId) ar.id.toInt else 0
    val size  = if (busConfig.useSize) ar.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) ar.len.toInt else 0
    val burst = if (busConfig.useBurst) ar.burst.toInt else 1
    val st = ArState(ar.addr.toBigInt, size, len, burst, id)
    val bpb = 1 << st.size
    for (beat <- 0 to st.len) {
      val base = (st.burst match {
        case 1 => st.addr + BigInt(bpb) * beat   // INCR
        case _ => st.addr                         // FIXED
      }).toLong
      val isLast = beat == st.len
      val bad = injectBusErrors && !BehavioralMem.decoded(base)
      rQueue += { () =>
        if (busConfig.useId)   axi.r.id   #= id
        axi.r.data #= (if (bad) BigInt(0) else readBeatData(base, bpb))
        if (busConfig.useResp) axi.r.resp #= (if (bad) 3 else 0)   // DECERR : OKAY
        if (busConfig.useLast) axi.r.last #= isLast
      }
    }
  }

  val rDriver = StreamDriver(axi.r, cd) { _ =>
    if (rQueue.nonEmpty) { rQueue.dequeue().apply(); true } else false
  }
  val arDriver = StreamReadyRandomizer(axi.ar, cd, () => rQueue.size < 8)

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
  // its aw (i.e. as the w handshake is consumed) — UNLESS the target address is
  // undecoded and `injectBusErrors` is set (task #189: an undecoded write is
  // dropped, not applied, matching a real bus that never reaches any backing
  // storage) — and only THEN enqueues the burst's B-response closure into a
  // b-queue driven by our own StreamDriver(b). Because the bytes are applied
  // strictly before the burst's B closure is ever dequeued/driven, the invariant
  // holds: once the DUT observes B for a write, any later refill read of that
  // address returns the written bytes (or, for an errored write, never observes a
  // phantom write at all).
  private case class AwState(addr: BigInt, size: Int, len: Int, burst: Int, id: Int, var beat: Int)
  private val awQueue = mutable.Queue[AwState]()
  private val wQueue  = mutable.Queue[(BigInt, BigInt, Boolean)]() // (data, strb, last)

  // Per-id queues of B-response closures (each sets b.id/b.resp). A closure is
  // enqueued ONLY after every byte of its burst has been written to `mem`.
  private val idCount = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val bQueue  = Array.fill(idCount)(mutable.Queue[() => Unit]())
  private var qPending = 0
  private val bQueueDepth = 4

  private def applyBeat(st: AwState, data: BigInt, strb: BigInt, bad: Boolean): Unit = {
    val bpb  = 1 << st.size
    val base = st.burst match {
      case 1 => st.addr + BigInt(bpb) * st.beat // INCR
      case _ => st.addr                          // FIXED
    }
    if (!bad) {
      for (i <- 0 until BehavioralMem.BYTES) {
        if (((strb >> i) & 1) == 1) {
          val byte = ((data >> (8 * i)) & 0xff).toInt.toByte
          mem.write((base + i).toLong, byte)
        }
      }
    }
    st.beat += 1
  }

  // Pair buffered aw + w beats in arrival order. Each paired w-beat is APPLIED to
  // `mem` immediately (unless undecoded+injectBusErrors); on the LAST beat of a
  // burst we enqueue that burst's B closure (so the bytes are guaranteed visible,
  // or guaranteed never-applied for an error, before B can be driven).
  private def update(): Unit = {
    while (awQueue.nonEmpty && wQueue.nonEmpty) {
      val st = awQueue.head
      val (data, strb, _) = wQueue.dequeue()
      val isLast = st.beat == st.len
      val bpb  = 1 << st.size
      val base = (st.burst match {
        case 1 => st.addr + BigInt(bpb) * st.beat
        case _ => st.addr
      }).toLong
      val bad = injectBusErrors && !BehavioralMem.decoded(base)
      applyBeat(st, data, strb, bad)
      if (isLast) {
        awQueue.dequeue()
        val id = st.id
        qPending += 1
        bQueue(id) += { () =>
          if (busConfig.useId) axi.b.id #= id
          if (busConfig.useResp) axi.b.resp #= (if (bad) 3 else 0)   // DECERR : OKAY
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
