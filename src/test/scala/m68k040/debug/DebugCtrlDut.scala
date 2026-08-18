package m68k040.debug

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Standalone host for `DebugCtrlPlugin`, following the same PluginHost-in-a-Component
  * pattern as `m68k040.mmu.MmuControlSpec.Dut`. `ParamPlugin` is deliberately absent:
  * `DebugCtrlPlugin` reads no `Global` key (spec section 0.9), so nothing would block on
  * it.
  *
  * `porCyclesArg` defaults to 4 so the debug power-on window is a handful of cycles in
  * simulation instead of the synthesis default of 16. */
class DebugCtrlDut(buildIdArg:   BigInt = BigInt(0x12345678L),
                   porCyclesArg: Int    = 4,
                   stageArg:     Int    = 1) extends Component {
  val db   = new Database
  val host = db on (new PluginHost)
  val dbg  = new DebugCtrlPlugin(buildId = buildIdArg, porCycles = porCyclesArg, stage = stageArg)
  db.on { host.asHostOf(Seq[FiberPlugin](dbg)) }

  def axi: DbgAxiLite = dbg.logic.dbgAxi
}

/** Minimal AXI4-Lite master stimulus for `DbgAxiLite`. Every method leaves the bus idle
  * with `bready`/`rready` high, so successive calls compose. */
object DbgAxiDriver {

  def idle(b: DbgAxiLite): Unit = {
    b.awvalid #= false; b.awaddr #= 0
    b.wvalid  #= false; b.wdata  #= 0; b.wstrb #= 0
    b.bready  #= true
    b.arvalid #= false; b.araddr #= 0
    b.rready  #= true
  }

  /** AW and W presented in the same cycle; returns BRESP. */
  def write(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long, strb: Int = 0xF): Int = {
    b.bready  #= true
    b.awaddr  #= addr; b.awvalid #= true
    b.wdata   #= data; b.wstrb   #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
    b.awvalid #= false; b.wvalid #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** AW accepted, then `gap` idle cycles, then W. Proves the AW skid register really
    * exists (spec 3.1: "capture AW and W independently"). */
  def writeAwFirst(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long,
                   strb: Int, gap: Int): Int = {
    b.bready  #= true
    b.awaddr  #= addr; b.awvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean)
    b.awvalid #= false
    cd.waitSampling(gap)
    b.wdata   #= data; b.wstrb #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.wready.toBoolean)
    b.wvalid  #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** W accepted, then `gap` idle cycles, then AW. */
  def writeWFirst(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long,
                  strb: Int, gap: Int): Int = {
    b.bready  #= true
    b.wdata   #= data; b.wstrb #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.wready.toBoolean)
    b.wvalid  #= false
    cd.waitSampling(gap)
    b.awaddr  #= addr; b.awvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean)
    b.awvalid #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** One read; returns RDATA as an unsigned 32-bit value in a Long. */
  def read(b: DbgAxiLite, cd: ClockDomain, addr: Long): Long = {
    b.rready  #= true
    b.araddr  #= addr; b.arvalid #= true
    cd.waitSamplingWhere(b.arready.toBoolean)
    b.arvalid #= false
    cd.waitSamplingWhere(b.rvalid.toBoolean)
    val data = b.rdata.toLong & 0xFFFFFFFFL
    cd.waitSampling()
    data
  }
}
