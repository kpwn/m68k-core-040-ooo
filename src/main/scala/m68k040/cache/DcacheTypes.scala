package m68k040.cache

import m68k040.isa.Size
import spinal.core._

/** Load request: a virtual address + access size. The cache translates (VIPT)
  * and reads. */
case class DLoadCmd() extends Bundle {
  val vaddr = UInt(32 bits)
  val size  = Size()
}

/** Load response: size-extracted (byte-lane, big-endian) data + fault. */
case class DLoadRsp() extends Bundle {
  val data  = Bits(32 bits)
  val fault = Bool()
}

/** Store command from the SQ drain: a PHYSICAL address (already translated),
  * the store data in the low bytes per size, and the access size. Write-through. */
case class DStoreCmd() extends Bundle {
  val paddr = UInt(32 bits)
  val data  = Bits(32 bits)
  val size  = Size()
}

/** D-cache service contract (spec 4.2). */
trait DcacheService {
  def loadCmd:  spinal.lib.Stream[DLoadCmd]   // virtual; cache translates (VIPT) + reads
  def loadRsp:  spinal.lib.Flow[DLoadRsp]     // fixed offset for a hit; valid late on a miss-refill
  def loadBusy: Bool                          // high while a refill is in flight (back-pressures loads)
  def store:    spinal.lib.Flow[DStoreCmd]    // write-through: update line if hit + write memory
  def storeAck: Bool                          // 1-cycle pulse when a write-through landed in memory (AXI B)
}

/** Big-endian byte-lane helpers shared by load extraction and store merge.
  *
  * m68k is big-endian: the byte at address A is the MOST significant byte of a
  * naturally-aligned multi-byte access. Within a 16-byte (128-bit) line, the
  * byte at line offset `o` (o in 0..15) lives in line bits [ (15-o)*8 +: 8 ] is
  * NOT how AXI memory is laid out — AXI/memory is byte-addressed little-endian in
  * the beat (byte o = bits [o*8 +: 8]). The cache stores the line exactly as the
  * AXI beat delivered it (byte o = line[o*8 +: 8]); big-endian semantics live only
  * in how a multi-byte LOAD assembles those bytes into the 32-bit result. */
object DcacheByteLane {
  /** Extract a size-typed value from a 128-bit line at byte offset `off` (0..15),
    * assembling bytes big-endian: result MSB = byte at `off`. Result is right-
    * justified in 32 bits (zero-extended above the access size). */
  def extract(line: Bits, off: UInt, size: Size.C): Bits = {
    // Per-byte view of the line (byte i = line[i*8 +: 8]).
    val bytes = line.subdivideIn(8 bits)   // bytes(0) = line[7:0] = mem byte at offset 0
    val b0 = bytes(off)
    val b1 = bytes(off + 1)
    val b2 = bytes(off + 2)
    val b3 = bytes(off + 3)
    val result = Bits(32 bits)
    result := B(0, 32 bits)
    switch(size) {
      is(Size.BYTE) { result := B(0, 24 bits) ## b0 }
      is(Size.WORD) { result := B(0, 16 bits) ## b0 ## b1 }
      is(Size.LONG) { result := b0 ## b1 ## b2 ## b3 }
    }
    result
  }

  /** Compute the 128-bit merge data + 16-bit byte strobe for a store of `size`
    * bytes at byte offset `off` (0..15). Store data is right-justified in 32 bits
    * (low bytes carry the value, big-endian: data[7:0] is the LEAST significant
    * byte = the highest address). */
  def storeData(off: UInt, size: Size.C, data: Bits): Bits = {
    val out   = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16) out(i) := B(0, 8 bits)
    // big-endian: byte at offset off = MSB of the value
    switch(size) {
      is(Size.BYTE) { out(off) := data(7 downto 0) }
      is(Size.WORD) { out(off) := data(15 downto 8); out(off + 1) := data(7 downto 0) }
      is(Size.LONG) {
        out(off) := data(31 downto 24); out(off + 1) := data(23 downto 16)
        out(off + 2) := data(15 downto 8); out(off + 3) := data(7 downto 0)
      }
    }
    out.asBits
  }

  /** 16-bit byte strobe for a store of `size` bytes at byte offset `off`. */
  def storeStrb(off: UInt, size: Size.C): Bits = {
    val strb = Bits(16 bits)
    val nbytes = UInt(3 bits)
    nbytes := 1
    switch(size) {
      is(Size.BYTE) { nbytes := 1 }
      is(Size.WORD) { nbytes := 2 }
      is(Size.LONG) { nbytes := 4 }
    }
    val bits = Vec(Bool(), 16)
    for (i <- 0 until 16) bits(i) := (U(i) >= off) && (U(i) < (off + nbytes))
    strb := bits.asBits
    strb
  }
}
