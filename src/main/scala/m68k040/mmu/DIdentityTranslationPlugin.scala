package m68k040.mmu

import m68k040.cache.{CacheMode, DTranslationCmd, DTranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** D-side identity VA==PA translation stub.  It implements the same one-entry
  * elastic response pipeline as the real DTLB so LSU timing does not depend on
  * which translation provider a test instantiates. */
class DIdentityTranslationPlugin extends FiberPlugin with DTranslationService {
  lazy val _req = Stream(DTranslationCmd())
  lazy val _rsp = Stream(DTranslationRsp())

  override def req: Stream[DTranslationCmd] = _req
  override def rsp: Stream[DTranslationRsp] = _rsp

  val logic = during build new Area {
    val rspValid = RegInit(False)
    val rspPayload = Reg(DTranslationRsp())

    _req.ready := !rspValid || _rsp.ready
    _rsp.valid := rspValid
    _rsp.payload := rspPayload

    when(_rsp.fire) { rspValid := False }
    when(_req.fire) {
      rspValid             := True
      rspPayload.ppn       := _req.payload.vpn
      rspPayload.cacheMode := CacheMode.WRITETHROUGH
      rspPayload.fault     := False
      rspPayload.token     := _req.payload.token
    }
  }
}
