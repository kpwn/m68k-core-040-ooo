// Adapted from NaxRiscv (naxriscv.compatibility.MultiportRam), SPDX MIT,
// "2023 Everybody". Trimmed to the async-read multi-write lowering used by this
// core (RAT / Freelist / ROB payload). Lowers a Mem with >1 write port and
// async reads into single-write distributed-RAM banks: XOR-trick for narrow
// data, Live-Value-Table (Mux) for wide data (>=10 bits).
package m68k040.hw

import spinal.core._
import spinal.core.internals._
import spinal.lib._

case class MemWriteCmd[T <: Data](payloadType: HardType[T], depth: Int) extends Bundle {
  val address = UInt(log2Up(depth) bits)
  val data    = payloadType()
}

case class MemRead[T <: Data](payloadType: HardType[T], depth: Int) extends Bundle with IMasterSlave {
  val cmd = Flow(UInt(log2Up(depth) bits))
  val rsp = payloadType()
  override def asMaster() = { master(cmd); in(rsp) }
}

case class RamAxyncMwIo[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Bundle {
  val writes = Vec.fill(writePorts)(slave(Flow(MemWriteCmd(payloadType, depth))))
  val read   = Vec.fill(readPorts)(slave(MemRead(payloadType, depth)))
}

object MultiportRam {
  /** HARD SYNTHESIS LIMIT (measured, not guessed).
    *
    * Vivado 2025.2 silently ABANDONS distributed-RAM (LUTRAM) inference for any
    * array carrying MORE THAN 15 asynchronous read ports, and falls back to the
    * dead-end "flip-flop vector + combinational read mux" form. Confirmed by a
    * directed out-of-context sweep on xcku5p (one 1W/NR array, depth 50, plain
    * `(* ram_style = "distributed" *)`):
    *
    *   NR =  8  ->    320 LUT as Memory,     0 FF   (RAM64X1D, clean)
    *   NR = 11  ->    440 LUT as Memory,     0 FF
    *   NR = 12  ->    480 LUT as Memory,     0 FF
    *   NR = 13  ->    520 LUT as Memory,     0 FF
    *   NR = 14  ->    560 LUT as Memory,     0 FF
    *   NR = 15  ->    600 LUT as Memory,     0 FF   <- last inferring point
    *   NR = 16  ->      0 LUT as Memory,  1600 FF + 6964 LUT as Logic  <- COLLAPSE
    *
    * The collapse is a pure read-PORT-COUNT cliff: it is independent of data
    * width (16 reads fails identically at width 32 / 16 / 8) and of depth (fails
    * identically at depth 50 and depth 64), and Vivado emits NO warning at all —
    * the array simply vanishes from the "Distributed RAM: Final Mapping Report".
    * Below the cliff the cost is exactly linear in the read-port count, so
    * REPLICATING an array to stay under the limit is area-NEUTRAL: it buys back
    * the LUTRAM mapping for free.
    *
    * Kept at 12 (not 15) for headroom against a different Vivado release or a
    * different primitive choice moving the cliff. */
  val maxReadPortsPerMem = 12
}

/** One logical single-write / many-async-read RAM, PHYSICALLY REPLICATED across
  * as many identical `Mem`s as it takes to keep every one of them at or below
  * `MultiportRam.maxReadPortsPerMem` async read ports (see the measured cliff
  * documented there).
  *
  * Every replica takes the SAME write (same enable/address/data) every cycle, so
  * all replicas hold identical contents AT EVERY ADDRESS THAT HAS BEEN WRITTEN
  * SINCE RESET, and a read of such an address is indistinguishable whichever
  * replica serves it. Replicas do NOT agree on never-written addresses (SpinalHDL
  * randomizes each `Mem` independently at time 0), so this is only sound where a
  * read of a never-written address is already a don't-care.
  *
  * ONLY USE FOR DIRECTLY-WRITTEN banks (the `RamAsyncMwMux` data banks): there a
  * read selects a bank via the LVT, so it reads back exactly the bank that a write
  * drove — into all of that bank's replicas alike. It is NOT sound for the XOR
  * banks, whose reads combine data ACROSS banks (see `RamAsyncMwXorCore`'s comment;
  * that case is handled by core-level splitting in `RamAsyncMwXor` instead).
  *
  * MUST be a plain `class`, NOT a `case class`: `RamAsyncMwXor`/`RamAsyncMwMux`
  * identify banks by REFERENCE (`filter(_ != storage)`, `indexOf(storage)`), and
  * a case class's structural equality would make distinct banks compare equal and
  * silently mis-wire the XOR cross-reads / the LVT bank index. */
class ReplicatedBank(payloadType: HardType[Bits], depth: Int, readCount: Int) extends Area {
  val replicaCount = scala.math.max(1,
    (readCount + MultiportRam.maxReadPortsPerMem - 1) / MultiportRam.maxReadPortsPerMem)
  private val perReplica = scala.math.max(1, (readCount + replicaCount - 1) / replicaCount)
  val mems = List.fill(replicaCount)(Mem.fill(depth)(payloadType))
  private var allocated = 0

  def write(enable: Bool, address: UInt, data: Bits): Unit =
    mems.foreach(_.write(address = address, data = data, enable = enable))

  /** Allocate the NEXT async read port of this bank. Callers must issue exactly
    * `readCount` of these (the constructor sized the replica set from that count);
    * the assert catches an over-allocation rather than letting it index past the
    * replica list or overfill the last replica past the inference cliff. */
  def readAsync(address: UInt): Bits = {
    assert(allocated < readCount || readCount == 0,
      s"ReplicatedBank: more readAsync() calls than the declared readCount=$readCount")
    val m = mems(allocated / perReplica)
    allocated += 1
    m.readAsync(address)
  }

  def addTags(spinalTags: Seq[SpinalTag]): Unit = mems.foreach(_.addTags(spinalTags))
}

/** ONE SELF-CONSISTENT XOR core: N-write / M-read async register file via the XOR
  * trick (one 1W RAM per write port). Precondition: no two write ports target the
  * same address in the same cycle.
  *
  * Banks here are PLAIN `Mem`s, deliberately NOT `ReplicatedBank`s. Bank-level
  * replication is UNSOUND for the XOR scheme and must never be reintroduced here:
  * the stored value of bank i is `data XOR (all OTHER banks at that address)`,
  * computed from the CROSS-READS taken at write time. If a later external read is
  * served by a DIFFERENT physical replica of those other banks, the reconstruction
  * XORs a different set of bits and returns garbage for every address whose
  * replicas have not (yet) been driven to identical contents — which in simulation
  * is every address never written, because SpinalHDL randomizes each `Mem`
  * independently at time 0. (Measured: doing exactly this turned
  * ExecuteLockStepSpec into 27/394, diverging at the very first commit with a
  * random-looking A7.) Read scaling is done at CORE level by `RamAsyncMwXor`
  * below, where each core reconstructs only from its OWN banks. */
case class RamAsyncMwXorCore[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  val rawType = HardType(Bits(payloadType.getBitsWidth bits))
  val ram = List.fill(writePorts)(Mem.fill(depth)(rawType))

  val writes = for ((port, storage) <- (io.writes, ram).zipped) yield new Area {
    val values = ram.filter(_ != storage).map(_.readAsync(port.address))
    val xored  = (port.data.asBits :: values).reduceBalancedTree(_ ^ _)
    storage.write(enable = port.valid, address = port.address, data = xored)
  }

  val reads = for (port <- io.read) yield new Area {
    val values = ram.map(_.readAsync(port.cmd.payload))
    val xored  = values.reduceBalancedTree(_ ^ _)
    port.rsp := xored.as(payloadType)
  }

  def addMemTags(spinalTags: Seq[SpinalTag]): this.type = { ram.foreach(_.addTags(spinalTags)); this }
}

/** N-write / M-read async XOR register file, transparently split across as many
  * SELF-CONSISTENT `RamAsyncMwXorCore`s as it takes to keep every bank at or below
  * `MultiportRam.maxReadPortsPerMem` async read ports (see the measured inference
  * cliff documented there).
  *
  * A bank inside a core carries `(writePorts - 1)` XOR cross-reads plus its share
  * of the external read ports, so a core can absorb `maxReadPortsPerMem -
  * (writePorts - 1)` external reads.
  *
  * SOUNDNESS (why this splits where bank-level replication does not): every core
  * gets EVERY write port, and reconstructs each read purely from its OWN banks
  * using cross-reads taken from those same banks — so each core independently
  * satisfies the XOR invariant, whatever its power-on contents. Cores agree with
  * each other for every address that has been written since reset, which after
  * `RegFilePlugin`'s init-zero boot sweep (a write of 0 to every address through
  * physical write port 0, reaching all cores) is every address. */
case class RamAsyncMwXor[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  // Headroom left for external reads after the mandatory cross-reads. If a caller
  // ever asks for so many WRITE ports that the cross-reads alone blow the cliff,
  // splitting reads cannot rescue it — fail loudly rather than silently emit a
  // flip-flop-vector register file again.
  private val readsPerCore = MultiportRam.maxReadPortsPerMem - (writePorts - 1)
  assert(readsPerCore >= 1,
    s"RamAsyncMwXor: $writePorts write ports need ${writePorts - 1} XOR cross-reads per bank, " +
    s"already at/over the ${MultiportRam.maxReadPortsPerMem}-read distributed-RAM inference limit; " +
    s"this lowering cannot keep the RAM in LUTRAM at that write-port count.")
  val coreCount = scala.math.max(1, (readPorts + readsPerCore - 1) / readsPerCore)
  private val perCore = scala.math.max(1, (readPorts + coreCount - 1) / coreCount)

  val cores = List.tabulate(coreCount) { c =>
    val lo = c * perCore
    val hi = scala.math.min(readPorts, lo + perCore)
    RamAsyncMwXorCore(payloadType, depth, writePorts, scala.math.max(0, hi - lo))
  }

  // Every core sees EVERY write (this is what keeps the cores mutually consistent
  // for all written addresses, and what makes each core's own XOR invariant hold).
  for (c <- cores; (dst, src) <- (c.io.writes, io.writes).zipped) {
    dst.valid := src.valid; dst.address := src.address; dst.data := src.data
  }
  for (r <- 0 until readPorts) {
    val core = cores(r / perCore)
    val cp   = core.io.read(r % perCore)
    cp.cmd.valid := io.read(r).cmd.valid
    cp.cmd.payload := io.read(r).cmd.payload
    io.read(r).rsp := cp.rsp
  }

  def addMemTags(spinalTags: Seq[SpinalTag]): this.type = { cores.foreach(_.addMemTags(spinalTags)); this }
}

/** N-write / M-read async register file via a Live-Value-Table (one bank per
  * write port + a narrow XOR table recording which bank last wrote each
  * address). Preferred for wide data. */
case class RamAsyncMwMux[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  val rawType = HardType(Bits(payloadType.getBitsWidth bits))
  // Data banks are written DIRECTLY (no XOR cross-reads), so each carries exactly
  // `readPorts` async reads.
  val ram = List.fill(writePorts)(new ReplicatedBank(rawType, depth, readPorts))

  val location = RamAsyncMwXor(
    payloadType = UInt(log2Up(writePorts) bits),
    depth = depth, writePorts = writePorts, readPorts = readPorts
  )

  val writes = for ((port, storage, loc) <- (io.writes, ram, location.io.writes).zipped) yield new Area {
    storage.write(enable = port.valid, address = port.address, data = port.data.asBits)
    loc.valid := port.valid
    loc.address := port.address
    loc.data := U(ram.indexOf(storage))
  }

  val reads = for ((port, loc) <- (io.read, location.io.read).zipped) yield new Area {
    loc.cmd.valid := port.cmd.valid
    loc.cmd.payload := port.cmd.payload
    val perBank = ram.map(_.readAsync(port.cmd.payload))
    port.rsp := Vec(perBank)(loc.rsp).as(payloadType)
  }

  def addMemTags(spinalTags: Seq[SpinalTag]): this.type = { ram.foreach(_.addTags(spinalTags)); this }
}

/** SpinalHDL elaboration phase: rewrite any Mem with >1 write port and async
  * reads into XOR/LVT banks (single-write distributed RAM). Multi-write Mems
  * with sync reads are not used in this core; we error rather than silently
  * leave them unsynthesizable. */
class MultiPortWritesSymplifier extends PhaseMemBlackboxing {
  override def doBlackboxing(pc: PhaseContext, typo: MemTopology): Unit = {
    if (typo.writes.size <= 1) return

    // Handle async-read multi-write Mems (XOR/LVT banks). readsAsync may be 0
    // (a write-only Mem, e.g. an NZVC/X PRF whose readers are all bypass-only in
    // a given build): the bank construction is valid with zero external read
    // ports — the XOR banks still need their internal cross-reads, which are not
    // part of `readsAsync`. The hard requirement is only that there are no SYNC
    // reads (those need the NaxRiscv sync branch, not used in this core).
    if (typo.readsSync.size == 0) {
      typo.writes.foreach(w => assert(w.mask == null, "MultiPortWritesSymplifier: masked writes unsupported"))
      typo.writes.foreach(w => assert(w.clockDomain == typo.writes.head.clockDomain))
      val cd = typo.writes.head.clockDomain
      import typo._
      val ctx = List(mem.parentScope.push(), cd.push())

      val io = if (typo.mem.width >= 10) {
        RamAsyncMwMux(Bits(mem.width bits), mem.wordCount, writes.size, readsAsync.size)
          .setCompositeName(mem).addMemTags(mem.getTags().toSeq).io
      } else {
        RamAsyncMwXor(Bits(mem.width bits), mem.wordCount, writes.size, readsAsync.size)
          .setCompositeName(mem).addMemTags(mem.getTags().toSeq).io
      }

      for ((dst, src) <- (io.writes, writes).zipped) {
        dst.valid.assignFrom(src.writeEnable)
        dst.address.assignFrom(src.address)
        dst.data.assignFrom(src.data)
      }
      for ((reworked, old) <- (io.read, readsAsync).zipped) {
        reworked.cmd.valid := True
        reworked.cmd.payload.assignFrom(old.address)
        wrapConsumers(typo, old, reworked.rsp)
      }

      mem.removeStatement()
      mem.foreachStatements(s => s.removeStatement())
      ctx.foreach(_.restore())
    } else {
      SpinalError(s"MultiPortWritesSymplifier: unsupported multi-write Mem topology " +
        s"(writes=${typo.writes.size}, readsAsync=${typo.readsAsync.size}, readsSync=${typo.readsSync.size}) for ${typo.mem}. " +
        s"Only async-read multi-write Mems are handled; add the sync branch from NaxRiscv if needed.")
    }
  }
}
