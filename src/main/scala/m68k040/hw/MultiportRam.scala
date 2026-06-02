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

/** N-write / M-read async register file via the XOR trick (one 1W RAM per write
  * port). Precondition: no two write ports target the same address in the same
  * cycle. */
case class RamAsyncMwXor[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
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

/** N-write / M-read async register file via a Live-Value-Table (one bank per
  * write port + a narrow XOR table recording which bank last wrote each
  * address). Preferred for wide data. */
case class RamAsyncMwMux[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  val rawType = HardType(Bits(payloadType.getBitsWidth bits))
  val ram = List.fill(writePorts)(Mem.fill(depth)(rawType))

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
