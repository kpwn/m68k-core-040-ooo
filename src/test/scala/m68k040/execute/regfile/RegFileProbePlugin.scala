package m68k040.execute.regfile

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

class RegFileProbePlugin extends FiberPlugin {
  var rd0: RegFileReadPort = null
  var rd1: RegFileReadPort = null
  var wr0: RegFileWritePort = null
  var wr1: RegFileWritePort = null
  var byp0: RegFileBypassPort = null
  var nzRd: RegFileReadPort = null
  var nzWr: RegFileWritePort = null
  var nzByp: RegFileBypassPort = null
  var xWr: RegFileWritePort = null

  during setup {
    val rf = host[IntRegFileService]
    rd0 = rf.newRead(); rd1 = rf.newRead()
    val grpKey = new Object
    wr0 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 1)
    wr1 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 0)
    byp0 = rf.newBypass()

    val nz = host[NzvcRegFileService]
    nzRd = nz.newRead(); nzWr = nz.newWrite(latency = 1); nzByp = nz.newBypass()

    val xrf = host[XRegFileService]
    xWr = xrf.newWrite(latency = 1)   // X needs >=1 write so its init sweep runs
  }

  val logic = during build new Area {
    val rd0Addr = in UInt (rd0.addr.getWidth bits); rd0.addr := rd0Addr
    val rd0Data = out Bits (rd0.data.getWidth bits); rd0Data := rd0.data
    val rd1Addr = in UInt (rd1.addr.getWidth bits); rd1.addr := rd1Addr
    val rd1Data = out Bits (rd1.data.getWidth bits); rd1Data := rd1.data
    val w0v = in Bool(); val w0a = in UInt (wr0.address.getWidth bits); val w0d = in Bits (wr0.data.getWidth bits)
    wr0.valid := w0v; wr0.address := w0a; wr0.data := w0d
    val w1v = in Bool(); val w1a = in UInt (wr1.address.getWidth bits); val w1d = in Bits (wr1.data.getWidth bits)
    wr1.valid := w1v; wr1.address := w1a; wr1.data := w1d
    val b0v = in Bool(); val b0a = in UInt (byp0.address.getWidth bits); val b0d = in Bits (byp0.data.getWidth bits)
    byp0.valid := b0v; byp0.address := b0a; byp0.data := b0d

    val nzRdAddr = in UInt (nzRd.addr.getWidth bits); nzRd.addr := nzRdAddr
    val nzRdData = out Bits (nzRd.data.getWidth bits); nzRdData := nzRd.data
    val nzWv = in Bool(); val nzWa = in UInt (nzWr.address.getWidth bits); val nzWd = in Bits (nzWr.data.getWidth bits)
    nzWr.valid := nzWv; nzWr.address := nzWa; nzWr.data := nzWd
    val nzBv = in Bool(); val nzBa = in UInt (nzByp.address.getWidth bits); val nzBd = in Bits (nzByp.data.getWidth bits)
    nzByp.valid := nzBv; nzByp.address := nzBa; nzByp.data := nzBd
    // X write tied idle (its init sweep still runs)
    xWr.valid := False; xWr.address := 0; xWr.data := 0
  }
}
