package m68k040.socket

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly

/** The production hot-door slice, reset absorber and raw socket wiring.
  * Kept in one place so boundary tests exercise the exact socket connection. */
object SocketHotReadBoundary {
  def connect(core: Axi4ReadOnly, port: SocketAxiI, rst: Bool,
              coreCd: ClockDomain, axiPorCd: ClockDomain,
              arFallThrough: Boolean): Unit = {
    val dhAbsorb = axiPorCd on new Area {
      val a = new AxiReadResetAbsorber()
      a.setName("axi_dh_reset_absorber")
      a.io.rstObserved := rst
      a.io.arFire      := port.arvalid && port.arready
      a.io.rLastFire   := port.rvalid && port.rready && port.rlast
    }
    val absorbDh = dhAbsorb.a.io.absorbing
    val dhSlice = coreCd on new Area {
      val dh = core.pipelined(
        ar = if (arFallThrough) StreamPipe.S2M else StreamPipe.FULL,
        r = StreamPipe.FULL)
    }
    val dh = dhSlice.dh
    port.arid    := dh.ar.payload.id
    port.araddr  := dh.ar.payload.addr
    port.arlen   := dh.ar.payload.len
    port.arsize  := dh.ar.payload.size
    port.arburst := dh.ar.payload.burst
    port.arvalid := dh.ar.valid && !absorbDh
    dh.ar.ready  := port.arready && !absorbDh
    dh.r.valid          := port.rvalid && !absorbDh
    dh.r.payload.id     := port.rid
    dh.r.payload.data   := SocketByteOrder.permuteData(port.rdata)
    dh.r.payload.resp   := port.rresp
    dh.r.payload.last   := port.rlast
    port.rready := dh.r.ready || absorbDh
  }
}
