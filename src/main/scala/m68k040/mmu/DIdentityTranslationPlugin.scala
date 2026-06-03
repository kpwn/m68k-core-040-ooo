package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** D-side identity VA==PA translation stub (DTLB placeholder). Registers
  * DTranslationService combinationally. Separate from the I-side identity stub so
  * the D-cache owns a distinct translation request port. */
class DIdentityTranslationPlugin extends FiberPlugin with DTranslationService {
  lazy val _req = TranslationReq()
  lazy val _rsp = TranslationRsp()

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  val logic = during build new Area {
    _rsp.ppn       := _req.vpn
    _rsp.cacheMode := CacheMode.CACHEABLE
    _rsp.fault     := False
  }
}
