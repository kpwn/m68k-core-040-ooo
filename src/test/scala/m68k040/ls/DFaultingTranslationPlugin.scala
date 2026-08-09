package m68k040.ls

import m68k040.cache.{CacheMode, DTranslationCmd, DTranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only D-side translation stub that FAULTS for a configured VPN. Identity
  * (ppn=vpn, no fault, always ready) for every other VPN. `faultVpn` is a sim-poked
  * register; when the request VPN matches and `faultEn` is set, rsp.fault asserts
  * (ready stays high — a resolved-with-fault response, like the DTLB's perm/non-
  * resident fault). Lets AccessFaultCaptureSpec drive a faulting LS access without
  * standing up the full DTLB + page table. */
class DFaultingTranslationPlugin extends FiberPlugin with DTranslationService {
  lazy val _req = Stream(DTranslationCmd())
  lazy val _rsp = Stream(DTranslationRsp())

  override def req: Stream[DTranslationCmd] = _req
  override def rsp: Stream[DTranslationRsp] = _rsp

  val logic = during build new Area {
    // Sim-poked control registers (held; the sim pokes them). Self-assign so they
    // have a driver (no UNASSIGNED REGISTER), while remaining sim-pokeable.
    val faultEn  = RegInit(False); faultEn.simPublic();  faultEn := faultEn
    val faultVpn = Reg(UInt(20 bits)) init 0; faultVpn.simPublic(); faultVpn := faultVpn
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
      rspPayload.fault     := faultEn && (_req.payload.vpn === faultVpn)
      rspPayload.token     := _req.payload.token
    }
  }
}
