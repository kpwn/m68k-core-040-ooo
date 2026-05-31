package m68k040.cache

import spinal.core._

/** Per-16-bit-word predecode result (one per chunk; 32 per 64-byte line).
  * `lenWords` (1..5 = 2/4/6/8/10 bytes) is meaningful only when `simple`. 4 bits. */
case class ChunkPredecode() extends Bundle {
  val simple   = Bool()
  val lenWords = UInt(3 bits)
}

object CacheMode extends SpinalEnum {
  val CACHEABLE, INHIBITED = newElement()
}

/** Upstream fetch request: a 32-bit PC. */
case class FetchCmd() extends Bundle {
  val pc = UInt(32 bits)
}

/** Fetch response: the 64-bit (8-byte) window at pc, plus fault. */
case class FetchRsp() extends Bundle {
  val pc    = UInt(32 bits)
  val data  = Bits(64 bits)   // little-endian window: data[7:0] = byte at pc+0 ... data[63:56] = byte at pc+7
                              // (architectural big-endian byte-order is the fetch/align stage's concern, deferred)
  val fault = Bool()
  val pred  = Vec(ChunkPredecode(), 4)   // predecode for the 4 words of the returned window
}

/** Translation request (virtual page number). */
case class TranslationReq() extends Bundle {
  val vpn        = UInt(20 bits)   // addr[31:12]
  val supervisor = Bool()
}

/** Translation response (physical page number + cache mode). */
case class TranslationRsp() extends Bundle {
  val ppn       = UInt(20 bits)
  val cacheMode = CacheMode()
  val fault     = Bool()
}
