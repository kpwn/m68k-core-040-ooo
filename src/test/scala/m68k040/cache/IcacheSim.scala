package m68k040.cache

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.sim.SparseMemory
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}

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

  /** Attach the shared AXI memory model backed by a SparseMemory preloaded so
    * reads return memByte(addr). Returns the started model.
    * Preloads `size` bytes from `base`.
    *
    * The shared model accepts Axi4ReadOnly directly and uses the same protocol
    * checker and response discipline as the full-core harnesses.
    */
  def attachMemory(
      axi: Axi4ReadOnly,
      cd: ClockDomain,
      base: Long,
      size: Int
  ): AxiMemModel = {
    val mem = SparseMemory()
    val img = Array.tabulate(size)(i => memByte(base + i).toByte)
    img.indices.foreach(i => mem.write(base + i, img(i)))

    AxiMemModel.attachReadOnly(axi, cd, AxiMemModelConfig(), sharedMem = mem)
  }

  /** Same as `attachMemory`, but also returns the backing `SparseMemory` so a test
    * can mutate it AFTER attach (e.g. simulating an MMIO register / device memory
    * changing underneath a resident I-cache line, bypassing the cache entirely) —
    * `attachMemory` above returns only the agent, with no way to poke memory post-
    * attach. Mirrors `DcacheSpec`'s `BehavioralMemAgent.pokeByte` used for the
    * analogous D-side "inhibited load bypasses a resident cached alias" test. */
  def attachMemoryMutable(
      axi: Axi4ReadOnly,
      cd: ClockDomain,
      base: Long,
      size: Int
  ): (AxiMemModel, SparseMemory) = {
    val mem = SparseMemory()
    val img = Array.tabulate(size)(i => memByte(base + i).toByte)
    img.indices.foreach(i => mem.write(base + i, img(i)))

    val agent = AxiMemModel.attachReadOnly(axi, cd, AxiMemModelConfig(), sharedMem = mem)
    (agent, mem)
  }

  def attachMemoryWithWords(axi: Axi4ReadOnly, cd: ClockDomain, base: Long,
                            words: Seq[Int]): AxiMemModel = {
    val mem = SparseMemory()
    words.zipWithIndex.foreach { case (w, i) =>
      mem.write(base + 2*i,     (w & 0xff).toByte)
      mem.write(base + 2*i + 1, ((w >> 8) & 0xff).toByte)
    }
    AxiMemModel.attachReadOnly(axi, cd, AxiMemModelConfig(), sharedMem = mem)
  }
}

/** Task icache-burst-fault-fix: a purpose-built AXI4-read-only responder that can
  * fault an EXPLICIT beat index of an EXPLICIT upcoming line-refill, independent of
  * address. Neither existing memory model can do this:
  *
  *  - `attachMemory`/`attachMemoryMutable` (`AxiMemModel`, above) has no explicit
  *    per-beat response-injection hook; its normal mapped reads are always OKAY.
  *  - `AxiMemModel`'s `injectBusErrors` decides bad-vs-good purely from
  *    `AxiMemModel.decoded(addr)`. Every decode boundary in that map is 64 KiB or
  *    256 MiB, and BOTH are exact multiples of the 64-byte I-cache line size — so a
  *    decode-based split can only ever place a WHOLE line's both beats on one side of
  *    a boundary; it can never land the boundary strictly BETWEEN a single line's
  *    beat 0 and beat 1. It is structurally incapable of producing the "beat 0 OKAY,
  *    beat 1 SLVERR/DECERR, same burst" scenario the icache-burst-fault-fix test
  *    needs.
  *
  * This driver instead lets a test arm a specific (lineBase, beatIdx) pair; the NEXT
  * AR whose (line-aligned) address matches gets that ONE beat's resp overridden to a
  * non-OKAY value (DECERR), with every other beat of that burst — and every other
  * burst — served as plain OKAY from an explicit per-line content map (`writeLine`;
  * unwritten lines read as all-zero). One AR in flight at a time is assumed (true of
  * the I-cache DUT, which never issues a second AR before the first burst's `last`).
  */
class BeatFaultAxiResponder(axi: Axi4ReadOnly, cd: ClockDomain) {
  private val lines = scala.collection.mutable.Map[Long, Array[Byte]]()
  private var armed: Option[(Long, Int)] = None   // (lineBase, faultBeatIdx), one-shot

  /** Install this line's 64 bytes of content, keyed by its (line-aligned) base
    * address. Read back beat-by-beat, 32 bytes/beat (matches the I-cache's 256-bit
    * AXI data width), little-endian within each beat. */
  def writeLine(lineBase: Long, bytes: Array[Byte]): Unit = {
    require(bytes.length == 64, "writeLine expects exactly one 64-byte I-cache line")
    lines(lineBase) = bytes
  }

  /** Arm the next AR whose line-aligned address equals `lineBase`: beat `beatIdx`
    * (0-based) of that burst returns DECERR instead of OKAY (data is still driven,
    * but the I-cache RTL must not trust it). Consumed on match (one-shot). */
  def armBeatFault(lineBase: Long, beatIdx: Int): Unit = armed = Some((lineBase, beatIdx))

  axi.ar.ready #= true
  axi.r.valid  #= false

  fork {
    while (true) {
      cd.waitSamplingWhere(axi.ar.valid.toBoolean)
      val lineBase = axi.ar.payload.addr.toBigInt.toLong
      val len      = axi.ar.payload.len.toInt
      val id       = axi.ar.payload.id.toBigInt
      val bytes    = lines.getOrElse(lineBase, Array.fill(64)(0.toByte))
      val (faultBase, faultBeat) = armed.getOrElse((-1L, -1))
      val doFault = lineBase == faultBase
      if (doFault) armed = None   // one-shot: consumed by this matching AR

      for (beat <- 0 to len) {
        var v = BigInt(0)
        for (i <- 0 until 32) v = v | (BigInt(bytes(beat * 32 + i).toInt & 0xff) << (8 * i))
        axi.r.valid         #= true
        axi.r.payload.data  #= v
        axi.r.payload.id    #= id
        axi.r.payload.last  #= (beat == len)
        axi.r.payload.resp  #= (if (doFault && beat == faultBeat) 3 else 0)   // DECERR : OKAY
        cd.waitSamplingWhere(axi.r.ready.toBoolean)
        axi.r.valid #= false
      }
    }
  }
}
