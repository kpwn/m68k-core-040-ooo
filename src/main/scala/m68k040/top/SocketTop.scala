package m68k040.top

import m68k040.{M68kParams, M68kSpinalConfig}
import m68k040.cache.{DcachePlugin, IcachePlugin}
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.debug.{DbgAxiLite, DebugCtrlPlugin, DebugRegMap}
import m68k040.mmu.{DtlbPlugin, ItlbPlugin, MmuControlPlugin}
import m68k040.socket._
import spinal.core._
import spinal.core.fiber.Fiber
import spinal.core.sim._
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
                    debugStage: Int = 5,
                    detailedPerf: Boolean = false,
                    ipcThroughput: Boolean = false,
                    ipcLateStore: Boolean = false,
                    pcRangeEnable: Boolean = true,
                    icachePredecodeWords: Int = 16) extends Component {
  require(!ipcLateStore || ipcThroughput, "late-store socket profile requires throughput options")
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

  // ── A domain `rst` CANNOT clear (see the AXI read absorber inside Fiber.build) ──
  // `resetKind = BOOT` means "no reset wire; the init value is the boot value" -- it
  // survives every CPU/JTAG reset and is cleared only by FPGA configuration.  Same
  // mechanism DebugCtrlPlugin uses for its debug POR domain.
  val axiPorCd = ClockDomain(clock  = clk,
                             config = ClockDomainConfig(clockEdge = RISING,
                                                        resetKind = BOOT))
  axiPorCd.setSynchronousWith(coreCd)

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
    val lsEu = new m68k040.execute.LsEuPlugin(
      alignedLoadFallThrough = ipcThroughput, earlyIntWakeup = ipcThroughput,
      sqSubwordForwarding = ipcThroughput, reserveLateStore = ipcThroughput,
      detachLateStore = ipcThroughput, forwardOnPublish = ipcThroughput,
      earlyNzvcWakeup = ipcThroughput, detachedStoreEntries = if(ipcThroughput) 4 else 1,
      earlyAutoStoreAddress = ipcLateStore, earlyStoreDataWake = ipcLateStore)
    val divEu = new m68k040.execute.DivEuPlugin
    val icache = new IcachePlugin(icachePredecodeWords)
    val merge  = new AxiDMergePlugin()
    val iplAck = new IplAckPlugin(enable = true)
    val periph = new PeripheralResetPlugin(enable = true,
                                           gateDispatch = SocketTopConfig.OPEN1_GATE_DISPATCH)
    val dbgCtrl = new DebugCtrlPlugin(buildId = dbgBuildId, stage = debugStage,
      detailedPerf = detailedPerf, pcRangeEnable = pcRangeEnable)

    val core = new M68kCore(exposeDebugPorts = true, detailedPerf = detailedPerf, plugins = Seq[FiberPlugin](
      new ParamPlugin(p),
      new MmuControlPlugin(),
      new m68k040.execute.FpuControlPlugin(),
      new m68k040.exception.InterruptControlPlugin(),
      new ItlbPlugin(),
      new DtlbPlugin(),
      icache,
      // Early virtual-set reads ARE enabled: the DTLB response qualifies them
      // through loadProbeResolve. Only pretranslated hints at probe launch are
      // disabled; the LSU has no physical address at that point.
      new DcachePlugin(socketMerged = true, allowPretranslatedProbeHints = false),
      new m68k040.frontend.BtbPlugin(),
      new m68k040.frontend.FtbPlugin(),
      new m68k040.frontend.RasPlugin(),
      new m68k040.frontend.GsharePlugin(retainRedirectHistory = ipcThroughput),
      new m68k040.frontend.FetchAlignPlugin(enableFetchDirected = true,
        trainSlot1Conditional = ipcThroughput, deferTakenSlot1Conditional = ipcThroughput),
      new m68k040.decode.DecodeStage(allowSlot1Prediction = ipcThroughput,
        fuseLongMoveLoads = ipcThroughput),
      new m68k040.rename.RenameStage(),
      new m68k040.dispatch.DispatchPlugin(detailedPerf = detailedPerf),
      new m68k040.rob.RobPlugin(detailedPerf = detailedPerf, pcRangeEnable = pcRangeEnable),
      new m68k040.execute.iq.IssueQueuePlugin(earlyStoreAddress = ipcThroughput,
        earlyAutoStoreAddress = ipcLateStore),
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
  val perf_trace = if (detailedPerf) Some(out(Bits(95 bits))) else None
  Fiber.build { perf_trace.foreach(_ := socket.core.perfTrace.get) }
  val dbg_axi = slave(DbgAxiLite(DebugRegMap.DBG_AW, DebugRegMap.DBG_DW))
  dbg_axi.setName("dbg_axi")
  val cpu_cold_reset_pulse = out Bool ()
  val cpu_cold_reset_hold  = out Bool ()
  val cpu_ram_window_lg2   = out UInt (6 bits)
  val cpu_mon_sense        = out UInt (7 bits)
  val init_done_seen       = in Bool ()

  // ── 2026-08-27 boot-investigation ILA taps (task: interrupt-recognition-
  // during-tight-loop bug) ──────────────────────────────────────────────
  // Deliberate, narrowly-scoped exception to D23's "only cpu_socket.vh ports"
  // rule, same class as the dbg_axi/debug-ctrl group above: cpu_socket.vh's
  // "group 7" (ILA-only debug-export, see rtl/soc/cpu_socket.vh) exists for
  // exactly this purpose but was never implemented on the CPU_M68K040 side
  // (rtl/soc/fpga_top_debug_ctrl.vh's CPU_M68K040 branch has no ILA
  // port-connection block -- confirmed absent). Rather than extend
  // synth/debug_ila.tcl's probe map (a wider, riskier change touching IP
  // regen), these 9 Bool + 1 PC port reuse 9 already-existing, currently-
  // unconnected-for-cpu040 1-bit `dbg_ila_*_w` slots plus `dbg_ila_rob_pc_w`
  // on the SoC side -- zero probe-map changes needed there, only new
  // `.dbg_ila_*_w(...)` connections in fpga_top_debug_ctrl.vh's
  // CPU_M68K040 branch. Always emitted (10 wires, no logic) rather than
  // gated on a constructor flag -- cheap enough not to need one, and the
  // SoC side decides via its own `ILA_ENABLE` ifdef whether to consume them.
  val dbg040_normalIrqGate      = out Bool ()
  val dbg040_flushing           = out Bool ()
  val dbg040_excIdle            = out Bool ()
  val dbg040_iplActive          = out Bool ()
  val dbg040_branchRedirect     = out Bool ()
  val dbg040_p0First            = out Bool ()
  val dbg040_preciseDrainBusyIn = out Bool ()
  val dbg040_inhibitedLoadBusyIn = out Bool ()
  val dbg040_interruptPending   = out Bool ()
  val dbg040_headPc             = out UInt (32 bits)
  // 2026-08-28 boot-investigation ILA taps (RAS occupancy / BTB+FTB training
  // payload — see M68kCore.scala's dbg040 Area and
  // docs/BUG_calibration_word_misplaced_0d00.md Part 24). Same "always
  // emitted, no logic, SoC decides via ILA_ENABLE" convention as above.
  val dbg040_rasPredValid       = out Bool ()
  val dbg040_rasPredTarget      = out UInt (32 bits)
  val dbg040_rasCount           = out UInt (7 bits)
  val dbg040_btbPredHitComb     = out Bool ()
  val dbg040_btbPredTargetComb  = out UInt (32 bits)
  val dbg040_btbUpdValid        = out Bool ()
  val dbg040_btbUpdPc           = out UInt (32 bits)
  val dbg040_btbUpdTarget       = out UInt (32 bits)
  val dbg040_ftbRspValid        = out Bool ()
  val dbg040_ftbRspHit          = out Bool ()
  val dbg040_ftbRspFramedOk     = out Bool ()
  val dbg040_ftbRspTarget       = out UInt (32 bits)
  // 2026-08-28 boot-investigation ILA taps, round 3 (FTQ lookup-command
  // window PC + registered FTQ head-entry fields — see M68kCore.scala's
  // dbg040 Area and docs/BUG_calibration_word_misplaced_0d00.md Part 24's
  // "Recommended next steps" #1/#2). Same convention as above.
  val dbg040_ftbCmdValid        = out Bool ()
  val dbg040_ftbCmdWindowPc     = out UInt (32 bits)
  val dbg040_decodePc           = out UInt (32 bits)
  val dbg040_ftqHeadBrPc        = out UInt (32 bits)
  val dbg040_ftqHeadTarget      = out UInt (32 bits)
  val dbg040_ftqHeadBrLen       = out UInt (4 bits)
  val dbg040_ftqConfirm         = out Bool ()
  val dbg040_ftqCount           = out UInt (6 bits)
  // 2026-09-05 p141 walker-stall ILA taps. Same four packed words the live
  // debug CSRs at 0x5090101C..0x50901028 already carry, plus the retire
  // counter -- exported as ports so a real ILA can record them as a TIME
  // SERIES rather than a single terminal sample.
  //
  // Why both a CSR and an ILA path for the same bits: the CSRs answer WHAT the
  // machine is stuck on, the ILA answers HOW IT GOT THERE. A frozen read cannot
  // distinguish a quiesce term that NEVER cleared from one that cleared and was
  // RE-ARMED by a grant hand-over, nor from a hand-over that landed one cycle
  // late relative to the drain entry. Those three imply different fixes and
  // have identical terminal state, which is exactly the ambiguity that has been
  // costing board cycles on this wedge.
  //
  // `macroCountLo` is the TRIGGER: the wedge freezes retire at a bit-identical
  // count every boot, so an equality match on it fires on the exact cycle the
  // machine stops, and a late TRIGGER_POSITION then fills the buffer with the
  // cycles BEFORE the freeze -- the only ones that carry information, since the
  // stall persists unchanged afterwards.
  val dbg040_stallDc            = out Bits (32 bits)
  val dbg040_stallGrant         = out Bits (32 bits)
  val dbg040_stallExc           = out Bits (32 bits)
  val dbg040_stallWalk          = out Bits (32 bits)
  val dbg040_macroCountLo       = out Bits (32 bits)

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
    // ── Post-reset AXI read-response absorber ───────────────────────────────────────
    //
    // A CPU reset must be able to RECOVER A HUNG MACHINE -- that is the whole purpose
    // of the debug reset.  But `rst` resets the entire core, including every
    // outstanding-transaction tracker, while the SoC side (L2C / crossbar / MIG) keeps
    // its state and still owes responses for reads issued BEFORE the reset.  Those
    // responses then have nobody willing to accept them: the I-cache raises `r.ready`
    // only for an id matching a LIVE MSHR (IcachePlugin: `axi.r.ready := demandRspMatch
    // || pfRspMatch`) and the D-cache only inside REFILL (`axi.r.ready :=
    // !refillWriteHold`) -- and the reset cleared both.  AXI R is in-order per id, so a
    // single un-acked stale beat blocks the read channel FOREVER: the reset-vector fetch
    // never receives data and the CPU never executes a single instruction.  (`b.ready`
    // is already held True globally in the D-cache, so the write channel was never
    // exposed to this; reads were the hole.)
    //
    // Fix: count live reads in `axiPorCd`, which `rst` cannot clear; arm on every reset;
    // and until the count returns to zero, accept-and-discard every read beat while
    // refusing to issue any new address.  Bounded by construction -- no new AR can be
    // added while absorbing, so the count is monotonically non-increasing and reaches
    // zero.  Steady-state cost is nil: with nothing outstanding, `absorbing` drops the
    // cycle after reset releases.
    val axiAbsorb = axiPorCd on new Area {
      // Instantiated HERE, in `axiPorCd`, on purpose: putting these in the core's own
      // reset domain would zero the very counters whose values are the thing `rst`
      // destroyed -- which is the bug being fixed.  See AxiReadResetAbsorber's header.
      val i = new AxiReadResetAbsorber()
      val d = new AxiReadResetAbsorber()
      i.setName("axi_i_reset_absorber")
      d.setName("axi_d_reset_absorber")
      i.io.rstObserved := rst
      i.io.arFire      := axi_i.arvalid && axi_i.arready
      i.io.rLastFire   := axi_i.rvalid && axi_i.rready && axi_i.rlast
      d.io.rstObserved := rst
      d.io.arFire      := axi_d.arvalid && axi_d.arready
      d.io.rLastFire   := axi_d.rvalid && axi_d.rready && axi_d.rlast
    }
    val absorbI = axiAbsorb.i.io.absorbing
    val absorbD = axiAbsorb.d.io.absorbing

    // ── AXI boundary register slice (2026-09-02, follow-up session) ──────────────────
    // Full-duplex register stage (`StreamPipe.FULL` = `s2mPipe().m2sPipe()`, SpinalHDL's
    // own `amba4.axi` `Axi4[ReadOnly].pipelined(...)` helper -- no hand-rolled skid buffer,
    // reusing proven first-party library code) inserted on every channel of BOTH `axi_i`
    // and `axi_d`, right at the CPU's own outermost socket port boundary. See
    // docs/BUG_calibration_word_misplaced_0d00.md (SoC repo) Part 86 / Part 87: Part 86
    // found the SoC's fabric (`u_xbar`/`u_l2c`) -> core worst timing paths are raw
    // combinational crossings with ZERO register at the AXI interface itself, reaching 30+
    // logic levels deep straight into `RobPlugin`'s fault-capture registers. This stage
    // guarantees no fabric-side combinational logic can reach a CPU-internal register (or
    // vice versa) without first passing through a flop physically inside this component,
    // immediately behind the port -- on ALL 5 channels of both masters for a clean,
    // symmetric boundary rather than a one-channel patch (`b`/`w`-ready are exactly as
    // structurally exposed as `r`/`b`-valid, per Part 86's own `ws_slv_reg`/`b_lock_reg`
    // findings on the fabric side).
    //
    // Placed in `coreCd` EXPLICITLY -- NOT the ambient `ClockDomain` `Fiber.build` would
    // otherwise capture at its own ambient call-site scope (which is `M68kSocketTop`'s own
    // default domain, not `coreCd`: the `coreCd on {}` wrap around `socket` above has
    // already closed by this point in the constructor) -- same reason `axiAbsorb` a few
    // lines up needed an explicit `on` wrap. Unlike `axiAbsorb`, though, this stage belongs
    // in `coreCd` ON PURPOSE, not `axiPorCd`: it must reset (and drop any in-flight beat)
    // exactly when the rest of the core's AXI consumer state does, so the read-reset-
    // absorber's port-level fire counting just above (which taps `axi_i`/`axi_d` UPSTREAM
    // of this stage) sees no new class of stuck state -- the reset-domain mismatch the
    // absorber exists to paper over is still exactly at the `axi_i`/`axi_d` pins, unmoved
    // by this purely-internal, purely-`coreCd` buffering stage.
    val axiRegSlice = coreCd on new Area {
      val ic = socket.icache.logic.axi.pipelined(ar = StreamPipe.FULL, r = StreamPipe.FULL)
      val dm = socket.merge.logic.axi.pipelined(
        aw = StreamPipe.FULL, w = StreamPipe.FULL, b = StreamPipe.FULL,
        ar = StreamPipe.FULL, r = StreamPipe.FULL)
    }

    // ── axi_i: the I-cache, permuted on r.data only (D3, D11) ───────────────────────
    val ic = axiRegSlice.ic
    axi_i.arid    := ic.ar.payload.id
    axi_i.araddr  := ic.ar.payload.addr
    axi_i.arlen   := ic.ar.payload.len
    axi_i.arsize  := ic.ar.payload.size
    axi_i.arburst := ic.ar.payload.burst
    axi_i.arvalid := ic.ar.valid && !absorbI
    ic.ar.ready   := axi_i.arready && !absorbI
    ic.r.valid          := axi_i.rvalid && !absorbI
    ic.r.payload.id     := axi_i.rid
    // THE ONLY permuted signal on this master. Applying it to an address would be a bug;
    // applying it twice would be a no-op that looks like a fix (SocketByteOrder is an
    // involution). It appears exactly once, here.
    ic.r.payload.data   := SocketByteOrder.permuteData(axi_i.rdata)
    ic.r.payload.resp   := axi_i.rresp
    ic.r.payload.last   := axi_i.rlast
    axi_i.rready  := ic.r.ready || absorbI   // absorb-and-discard stale beats

    // ── axi_d: the merged master, permuted on w.data / w.strb / r.data (D3) ─────────
    val dm = axiRegSlice.dm
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
    axi_d.arvalid := dm.ar.valid && !absorbD
    dm.ar.ready   := axi_d.arready && !absorbD
    dm.r.valid        := axi_d.rvalid && !absorbD
    dm.r.payload.id   := axi_d.rid
    dm.r.payload.data := SocketByteOrder.permuteData(axi_d.rdata)   // permuted (3 of 3)
    dm.r.payload.resp := axi_d.rresp
    dm.r.payload.last := axi_d.rlast
    axi_d.rready  := dm.r.ready || absorbD   // absorb-and-discard stale beats

    // ── Interrupt seam (D18) ─────────────────────────────────────────────────────────
    val bw = socket.core.plugins.collectFirst { case b: BackendWiringPlugin => b }.get
    bw.logic.iplInPort := cpu_ipl
    // D18: the socket declares NO vector input, so all seven levels take autovectors 25-31
    // -- what the Mac hardware does and what v1 does. `cpu_ipl` is ASYNCHRONOUS to the
    // core clock; its crossing lives inside BackendWiringPlugin, which is the correct
    // placement AND keeps the IPL compare cone non-foldable, which the OOC flow relies
    // on. 2026-09-18: that crossing was a SINGLE `RegNext` -- this comment used to call
    // it "the existing ... synchroniser", which it was not. It is now a two-flop
    // ASYNC_REG synchroniser plus a 2-of-2 level-agreement filter; see the long note at
    // `iplSync1` in FullCoreSynth.scala for why per-bit synchronising alone is not
    // enough for a multi-bit LEVEL.
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

    // ── 2026-08-27 boot-investigation ILA taps ──────────────────────────────────────
    // socket.core.dbg040.* are real OUTPUT PORTS of the M68kCore child component
    // (see M68kCore.scala's exposeDebugPorts doc comment) -- unlike a direct
    // `.plugins.collectFirst{...}.get.logic.X` read (which works for `flush.valid`
    // just above only because that's a WRITE into an already-declared `slave(...)`
    // port, not a read of a directionless internal signal), reading an internal
    // RobPlugin wire from OUT HERE would be a hierarchy violation.
    dbg040_normalIrqGate       := socket.core.dbg040.normalIrqGate
    dbg040_flushing            := socket.core.dbg040.flushing
    dbg040_excIdle             := socket.core.dbg040.excIdle
    dbg040_iplActive           := socket.core.dbg040.iplActive
    dbg040_branchRedirect      := socket.core.dbg040.branchRedirect
    dbg040_p0First             := socket.core.dbg040.p0First
    dbg040_preciseDrainBusyIn  := socket.core.dbg040.preciseDrainBusyIn
    dbg040_inhibitedLoadBusyIn := socket.core.dbg040.inhibitedLoadBusyIn
    dbg040_interruptPending    := socket.core.dbg040.interruptPending
    dbg040_headPc              := socket.core.dbg040.headPc
    dbg040_rasPredValid        := socket.core.dbg040.rasPredValid
    dbg040_rasPredTarget       := socket.core.dbg040.rasPredTarget
    dbg040_rasCount            := socket.core.dbg040.rasCount
    dbg040_btbPredHitComb      := socket.core.dbg040.btbPredHitComb
    dbg040_btbPredTargetComb   := socket.core.dbg040.btbPredTargetComb
    dbg040_btbUpdValid         := socket.core.dbg040.btbUpdValid
    dbg040_btbUpdPc            := socket.core.dbg040.btbUpdPc
    dbg040_btbUpdTarget        := socket.core.dbg040.btbUpdTarget
    dbg040_ftbRspValid         := socket.core.dbg040.ftbRspValid
    dbg040_ftbRspHit           := socket.core.dbg040.ftbRspHit
    dbg040_ftbRspFramedOk      := socket.core.dbg040.ftbRspFramedOk
    dbg040_ftbRspTarget        := socket.core.dbg040.ftbRspTarget
    dbg040_ftbCmdValid         := socket.core.dbg040.ftbCmdValid
    dbg040_ftbCmdWindowPc      := socket.core.dbg040.ftbCmdWindowPc
    dbg040_decodePc            := socket.core.dbg040.decodePc
    dbg040_ftqHeadBrPc         := socket.core.dbg040.ftqHeadBrPc
    dbg040_ftqHeadTarget       := socket.core.dbg040.ftqHeadTarget
    dbg040_ftqHeadBrLen        := socket.core.dbg040.ftqHeadBrLen
    dbg040_ftqConfirm          := socket.core.dbg040.ftqConfirm
    dbg040_ftqCount            := socket.core.dbg040.ftqCount
    dbg040_stallDc             := socket.core.dbg040.stallDc
    dbg040_stallGrant          := socket.core.dbg040.stallGrant
    dbg040_stallExc            := socket.core.dbg040.stallExc
    dbg040_stallWalk           := socket.core.dbg040.stallWalk
    dbg040_macroCountLo        := socket.core.dbg040.macroCountLo

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

object SocketIpcProfile {
  def enabled(name: String): Boolean = name match {
    case "baseline" => false
    case "throughput-v1" | "throughput-v2" => true
    case other => throw new IllegalArgumentException(s"Unknown CPU_IPC_PROFILE: $other")
  }
  def lateStore(name: String): Boolean = {
    enabled(name) // Validate even when queried independently.
    name == "throughput-v2"
  }
}

/** Build-time debug selection, independent of the execution/IPC profile. */
object SocketDebugProfile {
  def pcRangeEnabled(name: String): Boolean = name match {
    case "full" => true
    case "reduced" => false
    case other => throw new IllegalArgumentException(s"Unknown CPU_DEBUG_PROFILE: $other")
  }
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
    // Optional output directory lets diagnostic elaborations preserve the exact
    // generated sources/checkpoint inputs of an already-running implementation.
    require(args.length <= 1, "usage: GenSocketTopVerilog [output-directory]")
    val outputDirectory = args.headOption.getOrElse("generated")
    val detailedPerf = sys.env.getOrElse("PERF_DETAIL_ENABLE", "0") match {
      case "0" => false
      case "1" => true
      case value => throw new IllegalArgumentException(s"PERF_DETAIL_ENABLE must be 0 or 1, got $value")
    }
    val ipcProfile = sys.env.getOrElse("CPU_IPC_PROFILE", "baseline")
    val ipcThroughput = SocketIpcProfile.enabled(ipcProfile)
    val debugProfile = sys.env.getOrElse("CPU_DEBUG_PROFILE", "full")
    val pcRangeEnable = SocketDebugProfile.pcRangeEnabled(debugProfile)
    val icachePredecodeWords = m68k040.cache.IcachePredecodeConfig.fromEnvironment
    println(s"ICACHE_PREDECODE_WORDS=$icachePredecodeWords")
    println(s"CPU_IPC_PROFILE=$ipcProfile PERF_DETAIL_ENABLE=$detailedPerf CPU_DEBUG_PROFILE=$debugProfile")
    M68kSpinalConfig(targetDirectory = outputDirectory)
      .generateVerilog(new M68kSocketTop(M68kParams(), dbgBuildId,
        detailedPerf = detailedPerf, ipcThroughput = ipcThroughput,
        ipcLateStore = SocketIpcProfile.lateStore(ipcProfile), pcRangeEnable = pcRangeEnable,
        icachePredecodeWords = icachePredecodeWords))
    println(s"Generated $outputDirectory/M68kSocketTop.v")
  }
}
