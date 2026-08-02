package m68k040.cache

import m68k040.services.TranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only I-side translation stub with a SIM-POKEABLE cache-mode override for a
  * single configured VPN (identity ppn=vpn, never faults, always resolved this
  * cycle). `IdentityTranslationPlugin` hardcodes every response to
  * `CacheMode.WRITETHROUGH`, so it cannot drive the INHIBITED-mode directed tests
  * this file needs (an I-side fetch must never allocate into the I-cache when the
  * page/DTT-window is cache-inhibited, and must bypass a stale resident alias).
  * Mirrors `DFaultingTranslationPlugin`'s shape (fault-injection -> cache-mode
  * injection) so IcacheSpec can drive a specific cache mode without standing up a
  * full ITLB + page table walker. */
class ICacheModeTranslationPlugin extends FiberPlugin with TranslationService {
  lazy val _req = TranslationReq()
  lazy val _rsp = TranslationRsp()

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  val logic = during build new Area {
    // Sim-poked control registers (self-assigned so they have a driver / no
    // UNASSIGNED REGISTER warning, while remaining sim-pokeable).
    val cmodeEn  = RegInit(False); cmodeEn.simPublic();  cmodeEn  := cmodeEn
    val cmodeVpn = Reg(UInt(20 bits)) init 0; cmodeVpn.simPublic(); cmodeVpn := cmodeVpn
    val cmodeSel = Reg(CacheMode()) init CacheMode.INHIBITED
    cmodeSel.simPublic(); cmodeSel := cmodeSel
    val hit = cmodeEn && (_req.vpn === cmodeVpn)

    _rsp.ready     := True
    _rsp.ppn       := _req.vpn
    _rsp.cacheMode := Mux(hit, cmodeSel, CacheMode.WRITETHROUGH)
    _rsp.fault     := False
  }
}
