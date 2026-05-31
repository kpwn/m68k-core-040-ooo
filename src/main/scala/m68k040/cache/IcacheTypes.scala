package m68k040.cache

import spinal.core._

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
  val data  = Bits(64 bits)
  val fault = Bool()
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
