package m68k040.socket

import spinal.core._
import spinal.lib.IMasterSlave

/** The `axi_i_*` / `axi_d_*` master surfaces, EXACTLY as `cpu_socket.vh:98-142` declares them.
  *
  * ==Why not SpinalHDL's `Axi4` / `Axi4ReadOnly`==
  * SpinalHDL emits those as `<name>_ar_payload_addr`, `<name>_aw_payload_id`, ... -- confirmed
  * in the current netlist (`DcachePlugin_logic_axi_aw_payload_addr`, `itlbAxi_aw_payload_len`).
  * The socket declares `axi_d_awaddr`, `axi_d_awlen`, ..., which the stock bundle cannot
  * produce under any `setName`. This is the same reason the debug-ctrl work wrote its own
  * `DbgAxiLite` rather than using `AxiLite4`.
  *
  * ==D29: the sidebands do not EXIST here==
  * `prot`/`cache`/`lock`/`qos`/`region` are simply absent, not tied off. The core's own
  * `Axi4Config`s leave SpinalHDL's defaults on, so the netlist currently exports
  * `DcachePlugin_logic_axi_aw_payload_{region,lock,cache,qos,prot}` -- and drives them to `x`.
  * They are genuine don't-cares (the core has no notion of protection, cacheability hint or
  * QoS to express), so anything downstream reading them would be reading X. Declaring
  * socket-side constants instead was rejected deliberately: the socket declares no such
  * ports, so inventing them would export a wider surface than the contract, contradicting
  * D23's "only socket ports" rule in the same breath as satisfying it.
  *
  * ==D11==
  * `axi_i` is AR/R only -- the I-cache never writes -- and 256 bit wide. The SoC-side widening
  * is `SOC-1` and is out of this repository's scope entirely. */
case class SocketAxiI(dataWidth: Int = 256, idWidth: Int = 4, addressWidth: Int = 32)
    extends Bundle with IMasterSlave {
  val arid    = UInt(idWidth bits)
  val araddr  = UInt(addressWidth bits)
  val arlen   = UInt(8 bits)
  val arsize  = UInt(3 bits)
  val arburst = Bits(2 bits)
  val arvalid = Bool()
  val arready = Bool()
  val rid     = UInt(idWidth bits)
  val rdata   = Bits(dataWidth bits)
  val rresp   = Bits(2 bits)
  val rlast   = Bool()
  val rvalid  = Bool()
  val rready  = Bool()

  override def asMaster(): Unit = {
    out(arid, araddr, arlen, arsize, arburst, arvalid, rready)
    in(arready, rid, rdata, rresp, rlast, rvalid)
  }
}

/** The full read/write data master. See `SocketAxiI` for the naming and D29 rationale. */
case class SocketAxiD(dataWidth: Int = 128, idWidth: Int = 4, addressWidth: Int = 32)
    extends Bundle with IMasterSlave {
  val awid    = UInt(idWidth bits)
  val awaddr  = UInt(addressWidth bits)
  val awlen   = UInt(8 bits)
  val awsize  = UInt(3 bits)
  val awburst = Bits(2 bits)
  val awvalid = Bool()
  val awready = Bool()
  val wdata   = Bits(dataWidth bits)
  val wstrb   = Bits(dataWidth / 8 bits)
  val wlast   = Bool()
  val wvalid  = Bool()
  val wready  = Bool()
  val bid     = UInt(idWidth bits)
  val bresp   = Bits(2 bits)
  val bvalid  = Bool()
  val bready  = Bool()
  val arid    = UInt(idWidth bits)
  val araddr  = UInt(addressWidth bits)
  val arlen   = UInt(8 bits)
  val arsize  = UInt(3 bits)
  val arburst = Bits(2 bits)
  val arvalid = Bool()
  val arready = Bool()
  val rid     = UInt(idWidth bits)
  val rdata   = Bits(dataWidth bits)
  val rresp   = Bits(2 bits)
  val rlast   = Bool()
  val rvalid  = Bool()
  val rready  = Bool()

  override def asMaster(): Unit = {
    out(awid, awaddr, awlen, awsize, awburst, awvalid,
        wdata, wstrb, wlast, wvalid, bready,
        arid, araddr, arlen, arsize, arburst, arvalid, rready)
    in(awready, wready, bid, bresp, bvalid, arready, rid, rdata, rresp, rlast, rvalid)
  }
}
