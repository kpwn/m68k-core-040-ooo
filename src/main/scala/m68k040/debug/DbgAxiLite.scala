package m68k040.debug

import spinal.core._
import spinal.lib.IMasterSlave

/** The `dbg_axi_*` AXI4-Lite surface, EXACTLY as the authoritative socket contract
  * `macqd700-soc/rtl/soc/cpu_socket.vh:145-162` declares it.
  *
  * WHY NOT `spinal.lib.bus.amba4.axilite.AxiLite4`: that bundle carries `AWPROT`/
  * `ARPROT`, which this socket does not declare. Using it would add two ports the SoC
  * wrapper has nothing to bind, on an interface whose whole purpose is to be a drop-in
  * match for a contract owned by another repository. The signal set below is the socket's
  * signal set, no more and no less.
  *
  * WHY NOT `AxiLite4SlaveFactory`: the factory owns its own READY generation, and spec
  * section 3.1 requires READY to be LOW while the debug domain is in reset -- a property
  * that has to be structural, not incidental, because the deployed controller's original
  * bug was exactly an unconditionally-combinational `arready` (see
  * `macqd700-soc/cpu/rtl/core/debug/debug_reset_ctl.v`, the "~10 s Xicom timeout"
  * paragraph). `DebugCtrlPlugin` therefore drives a small explicit FSM. */
case class DbgAxiLite(addressWidth: Int = DebugRegMap.DBG_AW,
                      dataWidth:    Int = DebugRegMap.DBG_DW) extends Bundle with IMasterSlave {
  require(dataWidth % 8 == 0, s"DbgAxiLite dataWidth must be a byte multiple (got $dataWidth)")

  val awaddr  = UInt(addressWidth bits)
  val awvalid = Bool()
  val awready = Bool()
  val wdata   = Bits(dataWidth bits)
  val wstrb   = Bits(dataWidth / 8 bits)
  val wvalid  = Bool()
  val wready  = Bool()
  val bresp   = Bits(2 bits)
  val bvalid  = Bool()
  val bready  = Bool()
  val araddr  = UInt(addressWidth bits)
  val arvalid = Bool()
  val arready = Bool()
  val rdata   = Bits(dataWidth bits)
  val rresp   = Bits(2 bits)
  val rvalid  = Bool()
  val rready  = Bool()

  override def asMaster(): Unit = {
    out(awaddr, awvalid, wdata, wstrb, wvalid, bready, araddr, arvalid, rready)
    in(awready, wready, bresp, bvalid, arready, rdata, rresp, rvalid)
  }
}

object DbgAxiLite {
  /** AXI response encodings. This slave answers OKAY for every access, including
    * unmapped ones (spec 3.1: "return OKAY/zero for unmapped reads and OKAY/drop for
    * unmapped writes"). */
  def RESP_OKAY: Bits = B"00"
}
