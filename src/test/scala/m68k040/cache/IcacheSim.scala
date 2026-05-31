package m68k040.cache

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.bus.amba4.axi.sim.{Axi4ReadOnlySlaveAgent, SparseMemory}

object IcacheSim {
  /** Deterministic byte at a given address: byte = (addr * 7 + 0x11) & 0xff.
    * Lets tests predict fetched data without storing a full image. */
  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  /** The 64-bit little-endian window at an 8-aligned address: byte i of the
    * window is memByte(base+i). */
  def window64(base: Long): BigInt =
    (0 until 8).foldLeft(BigInt(0)) { (acc, i) =>
      acc | (BigInt(memByte(base + i)) << (8 * i))
    }

  /** Attach an Axi4ReadOnlySlaveAgent backed by a SparseMemory preloaded so
    * reads return memByte(addr).  Returns the started agent.
    * Preloads `size` bytes from `base`.
    *
    * Note: AxiMemorySim (1.14.1) requires a full Axi4 (read+write) bus and
    * cannot be constructed directly from an Axi4ReadOnly port.
    * Axi4ReadOnlySlaveAgent accepts Axi4ReadOnly directly and exposes a
    * readByte override point, which we use to serve from the preloaded
    * SparseMemory image.
    */
  def attachMemory(
      axi: Axi4ReadOnly,
      cd: ClockDomain,
      base: Long,
      size: Int
  ): Axi4ReadOnlySlaveAgent = {
    val mem = SparseMemory()
    val img = Array.tabulate(size)(i => memByte(base + i).toByte)
    mem.writeArray(base, img)

    new Axi4ReadOnlySlaveAgent(axi, cd) {
      override def readByte(address: BigInt, id: Int): Byte =
        mem.read(address.toLong)
    }
  }

  def attachMemoryWithWords(axi: Axi4ReadOnly, cd: ClockDomain, base: Long, words: Seq[Int]): Axi4ReadOnlySlaveAgent = {
    val mem = SparseMemory()
    words.zipWithIndex.foreach { case (w, i) =>
      mem.write(base + 2*i,     (w & 0xff).toByte)
      mem.write(base + 2*i + 1, ((w >> 8) & 0xff).toByte)
    }
    new Axi4ReadOnlySlaveAgent(axi, cd) {
      override def readByte(address: BigInt, id: Int): Byte = mem.read(address.toLong)
    }
  }
}
