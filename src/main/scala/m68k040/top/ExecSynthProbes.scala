package m68k040.top

import m68k040.execute.iq.{IssueQueueService, IqContext}
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService,
  RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesis-only: registers the IssueQueue's IO so an OOC run measures the
  * internal reg→reg paths (compaction, wakeup trigger-clear, age-select). */
class IqSynthProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]

    val pushValidIn = in Bool ()
    val push0In     = in(IqContext())
    val push1In     = in(IqContext())
    val slot1In     = in Bool ()
    val flushIn     = in Bool ()
    val ready0In    = in Bool ()
    val ready1In    = in Bool ()

    iq.push.valid      := RegNext(pushValidIn) init False
    iq.push.payload(0) := RegNext(push0In)
    iq.push.payload(1) := RegNext(push1In)
    iq.pushSlot1Valid  := RegNext(slot1In) init False
    iq.flushPort       := RegNext(flushIn) init False
    iq.issue(0).ready  := RegNext(ready0In) init False
    iq.issue(1).ready  := RegNext(ready1In) init False

    val pushReadyOut = out(RegNext(iq.push.ready) init False)
    val issue0Valid  = out(RegNext(iq.issue(0).valid) init False)
    val issue0Rob    = out(RegNext(iq.issue(0).payload.robId))
    val issue1Valid  = out(RegNext(iq.issue(1).valid) init False)
    val issue1Rob    = out(RegNext(iq.issue(1).payload.robId))
  }
}

/** Synthesis-only: allocates a representative PRF port set (int + NZVC + X) in
  * setup and registers its IO, so an OOC run measures the RF read/bypass/LVT-mux
  * + write-merge paths reg→reg. */
class PrfSynthProbePlugin extends FiberPlugin {
  var iR0, iR1: RegFileReadPort = null
  var iW0, iW1: RegFileWritePort = null
  var iB0: RegFileBypassPort = null
  var nR: RegFileReadPort = null
  var nW: RegFileWritePort = null
  var xW: RegFileWritePort = null

  during setup {
    val irf = host[IntRegFileService]
    iR0 = irf.newRead(); iR1 = irf.newRead()
    val k = new Object
    iW0 = irf.newWrite(latency = 1, sharingKey = k, priority = 1)
    iW1 = irf.newWrite(latency = 1)        // distinct key -> 2nd physical write port (LVT)
    iB0 = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nR = nz.newRead(); nW = nz.newWrite(latency = 1)
    val xrf = host[XRegFileService]
    xW = xrf.newWrite(latency = 1)
  }

  val logic = during build new Area {
    // int read 0
    iR0.addr := RegNext(in UInt (iR0.addr.getWidth bits))
    val iR0Data = out(RegNext(iR0.data))
    iR1.addr := RegNext(in UInt (iR1.addr.getWidth bits))
    val iR1Data = out(RegNext(iR1.data))
    // int writes
    iW0.valid := RegNext(in Bool ()) init False; iW0.address := RegNext(in UInt (iW0.address.getWidth bits)); iW0.data := RegNext(in Bits (iW0.data.getWidth bits))
    iW1.valid := RegNext(in Bool ()) init False; iW1.address := RegNext(in UInt (iW1.address.getWidth bits)); iW1.data := RegNext(in Bits (iW1.data.getWidth bits))
    iB0.valid := RegNext(in Bool ()) init False; iB0.address := RegNext(in UInt (iB0.address.getWidth bits)); iB0.data := RegNext(in Bits (iB0.data.getWidth bits))
    // nzvc
    nR.addr := RegNext(in UInt (nR.addr.getWidth bits))
    val nRData = out(RegNext(nR.data))
    nW.valid := RegNext(in Bool ()) init False; nW.address := RegNext(in UInt (nW.address.getWidth bits)); nW.data := RegNext(in Bits (nW.data.getWidth bits))
    // x (tie idle so its init sweep runs)
    xW.valid := False; xW.address := 0; xW.data := 0
  }
}
