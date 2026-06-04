package m68k040.ls

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only D-side translation stub that FAULTS for a configured VPN. Identity
  * (ppn=vpn, no fault, always ready) for every other VPN. `faultVpn` is a sim-poked
  * register; when the request VPN matches and `faultEn` is set, rsp.fault asserts
  * (ready stays high — a resolved-with-fault response, like the DTLB's perm/non-
  * resident fault). Lets AccessFaultCaptureSpec drive a faulting LS access without
  * standing up the full DTLB + page table. */
class DFaultingTranslationPlugin extends FiberPlugin with DTranslationService {
  lazy val _req = TranslationReq()
  lazy val _rsp = TranslationRsp()

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  val logic = during build new Area {
    // Sim-poked control registers (held; the sim pokes them). Self-assign so they
    // have a driver (no UNASSIGNED REGISTER), while remaining sim-pokeable.
    val faultEn  = RegInit(False); faultEn.simPublic();  faultEn := faultEn
    val faultVpn = Reg(UInt(20 bits)) init 0; faultVpn.simPublic(); faultVpn := faultVpn
    val hit = faultEn && (_req.vpn === faultVpn)
    _rsp.ready     := True
    _rsp.ppn       := _req.vpn
    _rsp.cacheMode := CacheMode.CACHEABLE
    _rsp.fault     := hit
  }
}
