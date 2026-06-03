package m68k040.cache

import m68k040.services.DTranslationService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: gives a standalone sim top-level IO to drive/observe the
  * DcacheService (load cmd/rsp/busy + store). Wiring lives in this plugin's own
  * `during build`. Also stands in for the LS EU as the translate-at-execute
  * requester: it drives the D-side translation request from the load cmd (the cache
  * itself only READS the response now), so a cache-only DUT still resolves the
  * single-driver DTranslationService.req. */
class DcacheProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val ds = host[DcacheService]

    val loadCmdIn  = slave(Stream(DLoadCmd()))
    val loadRspOut = master(Flow(DLoadRsp()))
    val loadBusyOut = out(Bool())
    val storeIn    = slave(Flow(DStoreCmd()))

    ds.loadCmd << loadCmdIn
    loadRspOut << ds.loadRsp
    loadBusyOut := ds.loadBusy
    ds.store << storeIn

    // drive the D-side translation request from the presented load (loads only here)
    val xlate = host[DTranslationService]
    xlate.req.valid      := loadCmdIn.valid
    xlate.req.vpn        := loadCmdIn.payload.vaddr(31 downto 12)
    xlate.req.supervisor := False
    xlate.req.write      := False
  }
}
