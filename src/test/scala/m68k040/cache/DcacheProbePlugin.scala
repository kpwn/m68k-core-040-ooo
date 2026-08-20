package m68k040.cache

import m68k040.services.DTranslationService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: gives a standalone sim top-level IO to drive/observe the
  * DcacheService (load cmd/rsp/busy + store). Wiring lives in this plugin's own
  * `during build`. It also drives the otherwise-unused standalone D-side identity
  * translator so the test host retains exactly one request producer; DcachePlugin
  * itself consumes only the resolved VA+PA command and never reads that response. */
class DcacheProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val ds = host[DcacheService]

    val loadProbeIn = slave(Stream(DLoadProbe()))
    val loadProbeCancelIn = slave(Flow(DLoadProbeCancel()))
    val loadCmdIn  = slave(Stream(DLoadCmd()))
    val loadRspOut = master(Flow(DLoadRsp()))
    val loadBusyOut = out(Bool())
    val storeIn    = slave(Stream(DStoreCmd()))
    // Task P5.4: cache-maintenance walk drive/observe. `maintCmd` is default-driven
    // idle inside DcachePlugin (allowOverride) since it has no real driver until Task
    // P5.5, so this overrides it rather than using `<<`.
    val maintCmdIn      = slave(Flow(CacheMaintCmd()))
    val maintDoneOut    = out(Bool())
    val maintErrorOut   = out(Bool())
    val maintQuiescedOut = out(Bool())

    ds.loadProbe << loadProbeIn
    ds.loadProbeCancel << loadProbeCancelIn
    ds.loadCmd << loadCmdIn
    loadRspOut << ds.loadRsp
    loadBusyOut := ds.loadBusy
    ds.store << storeIn
    ds.maintCmd.valid   := maintCmdIn.valid
    ds.maintCmd.payload := maintCmdIn.payload
    maintDoneOut        := ds.maintDone
    maintErrorOut       := ds.maintError
    maintQuiescedOut    := ds.maintQuiesced

    // Dcache consumes only resolved commands; keep the otherwise-unused identity
    // translator quiescent in this standalone cache DUT.
    val xlate = host[DTranslationService]
    xlate.req.valid              := False
    xlate.req.payload.vpn        := U(0, 20 bits)
    xlate.req.payload.supervisor := False
    xlate.req.payload.write      := False
    xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
    xlate.rsp.ready              := True
  }
}
