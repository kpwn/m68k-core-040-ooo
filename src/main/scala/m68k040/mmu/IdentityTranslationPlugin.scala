package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Identity VA==PA translation stub. Registers TranslationService combinationally.
  * Replaced by the real ITLB later — the I-cache is unchanged (resolves the same service). */
class IdentityTranslationPlugin extends FiberPlugin with TranslationService {
  // Lazily allocated in whatever component scope first accesses them.
  // The Dut drives req and reads rsp; the build fiber adds the combinational assignment.
  lazy val _req = TranslationReq()
  lazy val _rsp = TranslationRsp()

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  val logic = during build new Area {
    // Identity: physical page == virtual page, always cacheable, never fault,
    // always resolved this cycle (no walk).
    _rsp.ready     := True
    _rsp.ppn       := _req.vpn
    _rsp.cacheMode := CacheMode.CACHEABLE
    _rsp.fault     := False
  }
}
