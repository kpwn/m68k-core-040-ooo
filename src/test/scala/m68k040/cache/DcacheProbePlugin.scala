package m68k040.cache

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: gives a standalone sim top-level IO to drive/observe the
  * DcacheService (load cmd/rsp/busy + store). Wiring lives in this plugin's own
  * `during build`. */
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
  }
}
