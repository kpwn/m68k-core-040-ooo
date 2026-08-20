package m68k040.top

import m68k040.{M68kParams, M68kSpinalConfig}
import m68k040.cache.{DcachePlugin, IcachePlugin}
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.debug.{DbgAxiLite, DebugCtrlPlugin, DebugRegMap}
import m68k040.mmu.{DtlbPlugin, ItlbPlugin, MmuControlPlugin}
import m68k040.socket._
import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** The socket-conformant top level (design spec D3, D11, D21, D23, D29).
  *
  * ==What crosses this boundary, and what does not (D23)==
  * ONLY `cpu_socket.vh` ports. `M68kFullCoreSynth`'s 37 probe/test IOs do not: they stay on
  * that target, whose port surface and behaviour are unchanged and which remains the
  * OOC-synth / FMax gate. Here the anchor is the socket itself -- real AXI masters, IPL in,
  * `ipl_ack` out -- so nothing prunes and no artificial anchoring is needed. The
  * `redirect`/`resume` ports become internal under D12/D16 rather than being exported.
  *
  * ==The permutation, applied EXACTLY ONCE PER MASTER (D3)==
  * Only `w.data`, `w.strb` and `r.data`. Never address, id, len, size, burst, resp or last.
  * Applying it to an address would be a bug; applying it TWICE would be a no-op that looks
  * like a fix (it is an involution). `tools/socket/check_socket_netlist.py` and
  * `SocketTopByteOrderSpec` are the machine-checked forms of both statements.
  *
  * ==D21: reset kind and name==
  * The core stays ASYNC active-high; only the socket top's PORT is renamed `reset` -> `rst`,
  * and the `ClockDomainConfig` is declared EXPLICITLY here rather than inherited from a
  * SpinalHDL default a future upgrade could change silently.
  *
  * The divergence from the socket's literal "synchronous active-high" wording is named
  * rather than glossed: the SoC's `cpu_rst` is already core_clk-synchronous
  * (`fpga_top_cpu.vh:77,108-110`), so its deassertion is synchronous to the destination clock
  * by construction and there is no recovery/removal hazard at the async-reset consumers. An
  * async-reset consumer fed by a synchronously deasserted source is strictly MORE permissive
  * than a sync-reset one. Switching to `resetKind = SYNC` was rejected because it converts
  * every register's reset in the design -- a real, unquantified risk to the current
  * post-route result for zero functional gain.
  *
  * ==Deviation from the Task 13 brief's sketch: no `VioProbePlugin`==
  * `GenFullCoreSynthVerilog.buildWith` (the list this task is told to diff against) now
  * carries `new m68k040.debug.VioProbePlugin(enable = vioEnable, buildId = dbgBuildId)` as
  * its last plugin -- added by the separately-landed, still in-flight VIO/JTAG-debug plan
  * after this task's brief was written. That plugin's own doc comment says the socket top
  * "instantiates this with `enable=true` and no gate", but that wiring is explicitly that
  * OTHER plan's own future stage (its doc names it "V5"), not anything Tasks 1/3/4/5/9/10/12
  * of THIS plan produced, and this task's dispatch brief was explicit that
  * `src/main/scala/m68k040/debug/` belongs to a different in-flight plan and is out of
  * scope. Leaving it out entirely -- rather than guessing at `enable=true`'s wiring, or
  * even including it `enable=false` -- is the safer reading of "diff the two lists": the
  * addition is real, but it is not one of the "everything from Tasks 1, 3, 4, 5, 9, 10, 12"
  * this task is chartered to assemble. `DebugCtrlPlugin` (now through Stage 3 of the
  * separate debug-ctrl plan) is still wired below, exactly as
  * the brief's own sketch has it -- only the newer `VioProbePlugin` addition is excluded. */
class M68kSocketTop(p: M68kParams = M68kParams(),
                    dbgBuildId: BigInt = 0,
                    debugStage: Int = 4) extends Component {
  setDefinitionName("M68kSocketTop")
  noIoPrefix()

  val clk = in Bool ()
  val rst = in Bool ()

  val coreCd = ClockDomain(
    clock  = clk,
    reset  = rst,
    config = ClockDomainConfig(clockEdge        = RISING,
                               resetKind        = ASYNC,
                               resetActiveLevel = HIGH))

  val socket = coreCd on new Area {
    // ── Plugin list: GenFullCoreSynthVerilog's, plus the socket-only ones ────────────
    // ORDERING IS LOAD-BEARING and each constraint is stated at its own plugin:
    //   AxiDMergePlugin  after Dcache/Itlb/Dtlb, before ResetVectorPlugin and BackendWiring
    //   ResetVectorPlugin after AxiDMergePlugin and FetchAlignPlugin, before BackendWiring
    //   BackendWiringPlugin last of the wiring plugins (reads the arbiter's wedge and the
    //     reset vector's SSP); DebugCtrlPlugin is free-standing and stays last of all,
    //     exactly as GenFullCoreSynthVerilog.buildWith orders it.
    val eu0 = new m68k040.execute.AluEuPlugin
    val eu1 = new m68k040.execute.AluEuPlugin
    val branchEu = new m68k040.execute.BranchEuPlugin
    val lsEu = new m68k040.execute.LsEuPlugin
    val divEu = new m68k040.execute.DivEuPlugin
    val icache = new IcachePlugin()
    val merge  = new AxiDMergePlugin()
    val iplAck = new IplAckPlugin(enable = true)
    val periph = new PeripheralResetPlugin(enable = true,
                                           gateDispatch = SocketTopConfig.OPEN1_GATE_DISPATCH)
    val dbgCtrl = new DebugCtrlPlugin(buildId = dbgBuildId, stage = debugStage)

    val core = new M68kCore(Seq[FiberPlugin](
      new ParamPlugin(p),
      new MmuControlPlugin(),
      new m68k040.execute.FpuControlPlugin(),
      new m68k040.exception.InterruptControlPlugin(),
      new ItlbPlugin(socketMerged = true),
      new DtlbPlugin(socketMerged = true),
      icache,
      new DcachePlugin(socketMerged = true),
      new m68k040.frontend.BtbPlugin(),
      new m68k040.frontend.FtbPlugin(),
      new m68k040.frontend.RasPlugin(),
      new m68k040.frontend.GsharePlugin(),
      new m68k040.frontend.FetchAlignPlugin(enableFetchDirected = true),
      new m68k040.decode.DecodeStage(),
      new m68k040.rename.RenameStage(),
      new m68k040.dispatch.DispatchPlugin(),
      new m68k040.rob.RobPlugin(),
      new m68k040.execute.iq.IssueQueuePlugin(),
      eu0, eu1, branchEu, lsEu, divEu,
      new m68k040.execute.regfile.RegFilePluginInt(),
      new m68k040.execute.regfile.RegFilePluginNzvc(),
      new m68k040.execute.regfile.RegFilePluginX(),
      new m68k040.execute.regfile.RegFilePluginFp(),
      new m68k040.execute.regfile.RegFilePluginFpcc(),
      merge,
      new ResetVectorPlugin(enable = true),
      iplAck,
      periph,
      new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu, debugStage = debugStage),
      dbgCtrl
    ))
  }

  // ── Socket-facing port declarations ────────────────────────────────────────────────
  // Pure IO declarations only -- no plugin `.logic` Handle is read here. That distinction
  // is load-bearing (see the `Fiber.build` note below): declaring a port has no cross-
  // plugin dependency and is safe at construction time, but reading e.g.
  // `socket.icache.logic.axi` is a Handle access that blocks until IcachePlugin's OWN
  // `during build` task has produced it -- which cannot happen while the calling thread is
  // still inside `M68kSocketTop`'s own constructor, because SpinalHDL does not run ANY
  // `during build` task (for any plugin, in this nested `core`) until the WHOLE component
  // tree has finished constructing. Reading a `.logic` field here directly, synchronously,
  // is therefore a guaranteed self-deadlock: confirmed empirically (`SpinalHDL async engine
  // is stuck`, every registered plugin fiber parked on `spinal_elab_setup_start` because
  // this constructor never returns to let that barrier fire). The fix is below.
  val axi_i = master(SocketAxiI(dataWidth = 256, idWidth = m68k040.cache.AxiIds.ID_W))
  axi_i.setName("axi_i")
  val axi_d = master(SocketAxiD(dataWidth = 128, idWidth = m68k040.cache.AxiIds.ID_W))
  axi_d.setName("axi_d")
  val cpu_ipl = in UInt (3 bits)
  val ipl_ack = out Bool ()
  val cpu_peripheral_reset = out Bool ()
  val dbg_axi = slave(DbgAxiLite(DebugRegMap.DBG_AW, DebugRegMap.DBG_DW))
  dbg_axi.setName("dbg_axi")
  val cpu_cold_reset_pulse = out Bool ()
  val cpu_cold_reset_hold  = out Bool ()
  val cpu_ram_window_lg2   = out UInt (6 bits)
  val cpu_mon_sense        = out UInt (7 bits)
  val init_done_seen       = in Bool ()

  // ── Deferred wiring: everything that reads a plugin's `.logic` Handle ─────────────
  // Registered as a `spinal.core.fiber.Fiber.build` task -- the SAME generic async-fiber
  // primitive `FiberPlugin`'s own `during build` sugar is built on, just usable outside a
  // plugin/host context. It runs in the SAME build phase as every plugin's `during build`
  // Area, i.e. strictly AFTER the whole component tree (including `socket.core` and all its
  // plugins) has finished constructing, so every `.logic` Handle read below is guaranteed
  // already resolved by the time this closure executes. `Fiber.build` captures the ambient
  // Component/ClockDomain scope at the call site (still `M68kSocketTop`, not `socket.core`)
  // so every port written here lands on the correct component; no register is created by
  // any of this wiring (pure Bits/UInt renaming plus `SocketByteOrder`'s zero-depth
  // permutation), so the active ClockDomain is irrelevant to it in any case.
  Fiber.build {
    // ── axi_i: the I-cache, permuted on r.data only (D3, D11) ───────────────────────
    val ic = socket.icache.logic.axi
    axi_i.arid    := ic.ar.payload.id
    axi_i.araddr  := ic.ar.payload.addr
    axi_i.arlen   := ic.ar.payload.len
    axi_i.arsize  := ic.ar.payload.size
    axi_i.arburst := ic.ar.payload.burst
    axi_i.arvalid := ic.ar.valid
    ic.ar.ready   := axi_i.arready
    ic.r.valid          := axi_i.rvalid
    ic.r.payload.id     := axi_i.rid
    // THE ONLY permuted signal on this master. Applying it to an address would be a bug;
    // applying it twice would be a no-op that looks like a fix (SocketByteOrder is an
    // involution). It appears exactly once, here.
    ic.r.payload.data   := SocketByteOrder.permuteData(axi_i.rdata)
    ic.r.payload.resp   := axi_i.rresp
    ic.r.payload.last   := axi_i.rlast
    axi_i.rready  := ic.r.ready

    // ── axi_d: the merged master, permuted on w.data / w.strb / r.data (D3) ─────────
    val dm = socket.merge.logic.axi
    axi_d.awid    := dm.aw.payload.id
    axi_d.awaddr  := dm.aw.payload.addr
    axi_d.awlen   := dm.aw.payload.len
    axi_d.awsize  := dm.aw.payload.size
    axi_d.awburst := dm.aw.payload.burst
    axi_d.awvalid := dm.aw.valid
    dm.aw.ready   := axi_d.awready
    // Permuted (2 of 3). The D5 store-size derivation ran on the CORE-SIDE strobe, inside
    // DcachePlugin, and MUST have: nibble reversal preserves popcount and contiguity but not
    // the offset a run starts at, so deriving from what leaves here would give the
    // mirror-image address. These two transforms are order-dependent.
    axi_d.wdata   := SocketByteOrder.permuteData(dm.w.payload.data)
    axi_d.wstrb   := SocketByteOrder.permuteStrb(dm.w.payload.strb)
    axi_d.wlast   := dm.w.payload.last
    axi_d.wvalid  := dm.w.valid
    dm.w.ready    := axi_d.wready
    dm.b.valid        := axi_d.bvalid
    dm.b.payload.id   := axi_d.bid
    dm.b.payload.resp := axi_d.bresp
    axi_d.bready  := dm.b.ready
    axi_d.arid    := dm.ar.payload.id
    axi_d.araddr  := dm.ar.payload.addr
    axi_d.arlen   := dm.ar.payload.len
    axi_d.arsize  := dm.ar.payload.size
    axi_d.arburst := dm.ar.payload.burst
    axi_d.arvalid := dm.ar.valid
    dm.ar.ready   := axi_d.arready
    dm.r.valid        := axi_d.rvalid
    dm.r.payload.id   := axi_d.rid
    dm.r.payload.data := SocketByteOrder.permuteData(axi_d.rdata)   // permuted (3 of 3)
    dm.r.payload.resp := axi_d.rresp
    dm.r.payload.last := axi_d.rlast
    axi_d.rready  := dm.r.ready

    // ── Interrupt seam (D18) ─────────────────────────────────────────────────────────
    val bw = socket.core.plugins.collectFirst { case b: BackendWiringPlugin => b }.get
    bw.logic.iplInPort := cpu_ipl
    // D18: the socket declares NO vector input, so all seven levels take autovectors 25-31
    // -- what the Mac hardware does and what v1 does. The existing RegNext(...) init 0
    // synchroniser inside BackendWiringPlugin is retained untouched: it is the correct
    // placement AND it keeps the IPL compare cone non-foldable, which the OOC flow relies on.
    bw.logic.iackAvecIn   := True
    bw.logic.iackVectorIn := U(0, 8 bits)
    ipl_ack := socket.iplAck.logic.iplAck

    // ── RESET instruction output (D22) ──────────────────────────────────────────────
    cpu_peripheral_reset := socket.periph.logic.cpuPeripheralReset

    // ── Probe/test inputs of the core that never reach the socket (D23) ────────────
    // Driven to their idle values here so nothing dangles. They are INPUTS of M68kCore, not
    // socket ports; D12/D16 make redirect/resume internal rather than exported.
    val fa = socket.core.plugins.collectFirst {
      case f: m68k040.frontend.FetchAlignPlugin => f }.get
    fa.logic.redirect.valid   := False
    fa.logic.redirect.payload := U(0, 32 bits)
    fa.logic.resume.valid     := False
    fa.logic.resume.payload   := U(0, 32 bits)
    socket.icache.logic.invalidateAll := False
    socket.core.plugins.collectFirst { case r: m68k040.rob.RobPlugin => r }
      .get.logic.flush.valid := False

    // ── dbg_axi and the SoC-fabric control group pass straight through ─────────────
    // Socket groups 4 and 6. They are DEBUG-CTRL-OWNED (spec section 10) and this task adds,
    // removes and reinterprets nothing about them -- it only plumbs the ports the debug-ctrl
    // Stage 1 work already put on the core out to the socket boundary, because D23 says the
    // socket top exports the socket's ports and these ARE socket ports.
    //
    // `DebugCtrlPlugin.logic.dbgAxi` is `slave(DbgAxiLite(...))` -- a real IO of the NESTED
    // `socket.core` component, not of `M68kSocketTop` itself, so it does not auto-promote
    // the way `axi_i`/`axi_d` do not either: this is a genuine connection, field by field,
    // exactly like the two AXI masters above, not a passthrough that SpinalHDL does for
    // free. Both ends were declared `slave(...)` (the CPU is the AXI-lite slave at both this
    // boundary and the inner one), so the two bundles have IDENTICAL per-field directions
    // and cannot be joined with `<>` (which requires complementary master/slave roles) --
    // each field is wired by hand instead, exactly matching debug_regmap.def's PORT DIR
    // column.
    val da = socket.dbgCtrl.logic.dbgAxi
    // IN at both ends (host -> CPU): forward the socket input into the core's own input.
    da.awaddr  := dbg_axi.awaddr
    da.awvalid := dbg_axi.awvalid
    da.wdata   := dbg_axi.wdata
    da.wstrb   := dbg_axi.wstrb
    da.wvalid  := dbg_axi.wvalid
    da.bready  := dbg_axi.bready
    da.araddr  := dbg_axi.araddr
    da.arvalid := dbg_axi.arvalid
    da.rready  := dbg_axi.rready
    // OUT at both ends (CPU -> host): forward the core's own output onto the socket output.
    dbg_axi.awready := da.awready
    dbg_axi.wready  := da.wready
    dbg_axi.bresp   := da.bresp
    dbg_axi.bvalid  := da.bvalid
    dbg_axi.arready := da.arready
    dbg_axi.rdata   := da.rdata
    dbg_axi.rresp   := da.rresp
    dbg_axi.rvalid  := da.rvalid

    // The SoC-fabric control group (cpu_socket.vh section 6 / debug_regmap.def PORTs).
    cpu_cold_reset_pulse := socket.dbgCtrl.logic.coldResetPulse
    cpu_cold_reset_hold  := socket.dbgCtrl.logic.coldResetHold
    cpu_ram_window_lg2   := socket.dbgCtrl.logic.ramWindowLg2
    cpu_mon_sense        := socket.dbgCtrl.logic.monSense
    socket.dbgCtrl.logic.initDoneSeen := init_done_seen
  }
}

/** The `OPEN-1` answer, in ONE place, so the socket top and any future target cannot
  * disagree. Set from the signed-off addendum in the design spec's section 14.1 (commit
  * `5f75c51`, "resolve OPEN-1 -- cpu_peripheral_reset hold vs dispatch gating"): Option A,
  * output-only, no dispatch/retire gating. `PeripheralResetPlugin` itself hard-fails on
  * `true` (Option B has no `RobPlugin` wiring in this tree), so `false` is not merely this
  * task's choice -- it is the only value that elaborates at all. */
object SocketTopConfig {
  val OPEN1_GATE_DISPATCH: Boolean = false
}

object GenSocketTopVerilog {
  def main(args: Array[String]): Unit = {
    val dbgBuildId: BigInt = sys.env.get("DBG_BUILD_ID") match {
      case None    => BigInt(0)
      case Some(s) =>
        val hex = s.trim.stripPrefix("0x").stripPrefix("0X")
        require(hex.nonEmpty && hex.forall(c => "0123456789abcdefABCDEF".contains(c)),
          s"DBG_BUILD_ID must be hexadecimal (got '$s')")
        BigInt(hex, 16)
    }
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kSocketTop(M68kParams(), dbgBuildId))
    println("Generated generated/M68kSocketTop.v")
  }
}
