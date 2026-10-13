package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._

/** TLB victim-buffer PAYLOAD half as an n x 25 async-read distributed RAM
  * (`CPU_TLB_VIC_PAYLOAD_LUTRAM`). Replaces the per-slot ppn/cmode/wProt/pgSup/modif
  * flops and their MuxOH: one write port (the staged insert), one async read port
  * addressed by the encoded registered s2 one-hot. The tag half stays in flops. */
class VictimPayloadRam(n: Int) extends Area {
  val cmW   = log2Up(CacheMode.elements.length)
  val width = 20 + cmW + 3
  val mem   = Mem(Bits(width bits), n)

  def write(addr: UInt, en: Bool, e: TlbEntry): Unit = {
    val d = e.modified ## e.supervisor ## e.writeProt ## e.cacheMode.asBits ## e.ppn.asBits
    require(widthOf(d) == width, s"VictimPayloadRam: packed width ${widthOf(d)} != $width")
    mem.write(addr, d, en)
  }

  def read(addr: UInt): TlbEntry = {
    val rd = mem.readAsync(addr)
    val e  = TlbEntry()
    e.vpnTag     := 0
    e.ppn        := rd(19 downto 0).asUInt
    e.cacheMode.assignFromBits(rd(20 + cmW - 1 downto 20))
    e.writeProt  := rd(20 + cmW)
    e.supervisor := rd(21 + cmW)
    e.modified   := rd(22 + cmW)
    e
  }
}
