package m68k040.sim

import m68k040.cache.{DLoadCmd, DLoadRsp, DStoreCmd}
import m68k040.services.WalkerDcacheClient
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.lib.sim.SparseMemory

/** The byte-poke/peek surface shared by `BehavioralMemAgent`, `AxiMemModel` and
  * `DcacheClientMemAgent`.
  *
  * Test helpers that build a page table used to take `BehavioralMemAgent` concretely,
  * which was fine while the walker had its own AXI memory. Now the page table has to live
  * in whichever memory the walk actually reaches -- the D-side `AxiMemModel` in a DUT that
  * has a D-cache, a `DcacheClientMemAgent` in one that does not -- so those helpers take
  * this structural view instead. `-language:reflectiveCalls` is already on (`build.sbt`),
  * and this is test setup, so the reflective dispatch cost is irrelevant. */
object SimMem {
  type ByteMem = {
    def pokeByte(addr: Long, value: Int): Unit
    def peekByte(addr: Long): Int
  }
}

/** DUT-side exposure of a table walker's `DcacheService` client port pair.
  *
  * Since the walker/D-cache passthrough change, `TableWalker`'s descriptor reads and the
  * TLB plugins' U/M descriptor writeback are `DcacheService` client traffic rather than a
  * private AXI master. In a full core they are arbitrated onto the D-cache's ports by
  * `LsEuPlugin`. A DUT that hosts a TLB plugin but NO `DcachePlugin`/`LsEuPlugin` — every
  * standalone MMU, I-cache and fetch-align test — has no arbiter to answer them, so it
  * pulls the ports up to its own IO instead and lets `DcacheClientMemAgent` answer them
  * out of a `SparseMemory`. That is the direct replacement for attaching a
  * `BehavioralMemAgent` to the old `walkerAxi` port.
  *
  * This is a `FiberPlugin`, not a plain `Area`, and that is load-bearing: the TLB
  * plugin allocates its client ports in `during setup`, which has NOT run yet while the
  * DUT `Component`'s own body executes. Constructing the ports straight in the DUT body
  * dereferences a null. Being a plugin puts this in the same `build` phase the
  * `AxiDMergePlugin`-reads-`walkerAxi` precedent relies on: every `setup` runs before
  * any `build`, so by the time this elaborates the ports exist.
  *
  * Add it to the DUT's plugin list, once per walker. The plugin-side defaults are
  * `allowOverride`, so connecting them here replaces the idle drives rather than
  * colliding with them.
  *
  * DO NOT use this in a DUT that also hosts `LsEuPlugin` — there the arbiter is the
  * driver and this would be a second one. */
class WalkerDcacheSimIo(c: => WalkerDcacheClient, portName: String) extends FiberPlugin {
  val logic = during build new Area {
    val cmd = master(Stream(DLoadCmd())).setName(portName + "Cmd")
    cmd << c.walkLoadCmd
    val rsp = slave(Flow(DLoadRsp())).setName(portName + "Rsp")
    c.walkLoadRsp << rsp
    val st = master(Stream(DStoreCmd())).setName(portName + "St")
    st << c.walkStore
    val stAck = in Bool () setName (portName + "StAck")
    c.walkStoreAck := stAck
    val stErr = in Bool () setName (portName + "StErr")
    c.walkStoreErr := stErr
  }
}

/** Behavioural `DcacheService` client responder: the `Stream`/`Flow` analogue of
  * `BehavioralMemAgent`.
  *
  * Answers `DLoadCmd` out of a `SparseMemory` with the SAME big-endian assembly the real
  * `DcacheByteLane.extract` performs (byte at the lowest address is the value's MSB —
  * m68k memory is big-endian architecturally), and applies a `DStoreCmd` through its
  * `strb`/`lineData` pair, which is the only store shape a walker ever emits.
  *
  * `sharedMem` is the important knob: passing the DUT's own data memory makes the walk
  * observe the same image the rest of the test writes, which is what the previous
  * `BehavioralMemAgent(walkerAxi, cd, sharedMem = ...)` sites did.
  *
  * @param latency response latency in cycles, measured from the command handshake. The
  *                default of 1 mirrors a D-cache hit; a larger value models a miss. */
class DcacheClientMemAgent(cmdP: Stream[DLoadCmd], rspP: Flow[DLoadRsp],
                           stP: Stream[DStoreCmd], stAckP: Bool, stErrP: Bool,
                           cd: ClockDomain,
                           sharedMem: SparseMemory = null,
                           latency: Int = 1,
                           readyEveryCycle: Boolean = true) {
  /** Convenience form for the common case: a whole `WalkerDcacheSimIo`. */
  def this(io: WalkerDcacheSimIo, cd: ClockDomain, sharedMem: SparseMemory,
           latency: Int, readyEveryCycle: Boolean) =
    this(io.logic.cmd, io.logic.rsp, io.logic.st, io.logic.stAck, io.logic.stErr,
         cd, sharedMem, latency, readyEveryCycle)
  def this(io: WalkerDcacheSimIo, cd: ClockDomain, sharedMem: SparseMemory) =
    this(io, cd, sharedMem, 1, true)
  def this(io: WalkerDcacheSimIo, cd: ClockDomain) = this(io, cd, null, 1, true)

  private object io {
    val cmd = cmdP; val rsp = rspP; val st = stP; val stAck = stAckP; val stErr = stErrP
  }
  private val hasStore = stP != null

  val mem: SparseMemory = if (sharedMem != null) sharedMem else SparseMemory()

  /** Number of load commands accepted. The direct replacement for the
    * `walkerAxi.ar.valid && walkerAxi.ar.ready` counting the migrated tests used to do. */
  var loadCount: Int = 0
  /** Number of store commands accepted. */
  var storeCount: Int = 0
  /** Byte addresses of every accepted load command, in order. */
  val loadAddrs = scala.collection.mutable.ArrayBuffer[Long]()

  def pokeByte(addr: Long, value: Int): Unit = mem.write(addr, value.toByte)
  def peekByte(addr: Long): Int = mem.read(addr).toInt & 0xff

  /** Big-endian longword, the way a real supervisor `move.l` wrote it. */
  def pokeLong(addr: Long, value: Long): Unit = {
    for (i <- 0 until 4) mem.write(addr + i, ((value >> (8 * (3 - i))) & 0xff).toByte)
  }
  def peekLong(addr: Long): Long = {
    var v = 0L
    for (i <- 0 until 4) v = (v << 8) | (mem.read(addr + i).toLong & 0xff)
    v
  }

  /** EXACTLY what `DcacheByteLane.extract` computes, wrap included.
    *
    * The real D-cache serves a load out of the ONE 16-byte line that contains `addr`,
    * indexing it with a 4-BIT line offset -- so a WORD at line offset 15, or a LONG at
    * 13/14/15, takes its trailing bytes from the line's own HEAD instead of from the
    * next line. This agent used to read the flat byte array (`mem.read(addr + i)`), which
    * silently DID cross the boundary and therefore could never reproduce that wrap: a
    * walker or exception sequencer presenting a straddling access looked correct here and
    * wrong only on real hardware. That blindness is the same shape as the D-cache-side
    * defects it is meant to model, so the model is corrected rather than the check
    * relaxed. Non-straddling accesses (every access any passing test made before) are
    * bit-for-bit unchanged.
    *
    * A requester that legitimately wants the raw line sets `DLoadCmd.lineOnly` and reads
    * `DLoadRsp.line`, which this agent still fills from flat memory. */
  private def readBE(addr: Long, nbytes: Int): BigInt = {
    val lineBase = addr & ~0xfL
    val off      = (addr & 0xfL).toInt
    var v = BigInt(0)
    for (i <- 0 until nbytes) v = (v << 8) | BigInt(mem.read(lineBase + ((off + i) & 0xf)).toInt & 0xff)
    v
  }

  // Pending load responses: (cyclesRemaining, data, line).
  private val pending = scala.collection.mutable.Queue[(Int, BigInt, BigInt)]()
  private var stAckPending = 0

  io.cmd.ready #= readyEveryCycle
  io.rsp.valid #= false
  if (hasStore) {
    io.st.ready #= readyEveryCycle
    io.stAck #= false
    io.stErr #= false
  }

  cd.onSamplings {
    // ---- responses due this cycle ----
    var fired = false
    if (pending.nonEmpty && pending.head._1 <= 0) {
      val (_, d, l) = pending.dequeue()
      io.rsp.valid #= true
      io.rsp.payload.data #= d
      io.rsp.payload.line #= l
      io.rsp.payload.fault #= false
      fired = true
    }
    if (!fired) io.rsp.valid #= false
    // age the rest
    val aged = pending.map { case (c, d, l) => (c - 1, d, l) }.toList
    pending.clear(); aged.foreach(pending.enqueue(_))

    if (hasStore) {
      io.stAck #= (stAckPending == 1)
      if (stAckPending > 0) stAckPending -= 1
    }

    // ---- accept a new load command ----
    if (io.cmd.valid.toBoolean && io.cmd.ready.toBoolean) {
      val paddr = io.cmd.payload.paddr.toLong
      val sizeOrd = io.cmd.payload.size.toEnum
      val nbytes = sizeOrd match {
        case m68k040.isa.Size.BYTE => 1
        case m68k040.isa.Size.WORD => 2
        case _                     => 4
      }
      val lineBase = paddr & ~0xfL
      // The line is stored exactly as an AXI beat delivers it: byte o at bits [o*8 +: 8].
      var line = BigInt(0)
      for (i <- 15 to 0 by -1) line = (line << 8) | BigInt(mem.read(lineBase + i).toInt & 0xff)
      pending.enqueue((latency, readBE(paddr, nbytes), line))
      loadCount += 1
      loadAddrs += paddr
    }

    // ---- accept a new store command ----
    if (hasStore && io.st.valid.toBoolean && io.st.ready.toBoolean) {
      val paddr = io.st.payload.paddr.toLong
      if (io.st.payload.useStrb.toBoolean) {
        val strb = io.st.payload.strb.toLong
        val data = io.st.payload.lineData.toBigInt
        val base = paddr & ~0xfL
        for (i <- 0 until 16) {
          if (((strb >> i) & 1) != 0) {
            mem.write(base + i, ((data >> (8 * i)) & 0xff).toInt.toByte)
          }
        }
      } else {
        // A walker never emits this shape; support it anyway so the agent is a
        // general-purpose client responder rather than a walker-only special case.
        val nbytes = io.st.payload.size.toEnum match {
          case m68k040.isa.Size.BYTE => 1
          case m68k040.isa.Size.WORD => 2
          case _                     => 4
        }
        val d = io.st.payload.data.toLong
        for (i <- 0 until nbytes) {
          mem.write(paddr + i, ((d >> (8 * (nbytes - 1 - i))) & 0xff).toInt.toByte)
        }
      }
      storeCount += 1
      stAckPending = latency
    }
  }
}
