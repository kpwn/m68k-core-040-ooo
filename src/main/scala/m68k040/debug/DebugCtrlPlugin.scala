package m68k040.debug

import m68k040.services.{DebugCommitService, DebugSystemStateService, DebugMemoryService,
  DebugHistoryService, DebugMemoryCommand, FrontendDebugMatchService}
import m68k040.services.CommittedMapService
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService,
  RegFileReadPort, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib.slave
import spinal.lib.misc.plugin.FiberPlugin

/** The CPU-side debug/control AXI4-Lite slave (design spec
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`, Stage 1).
  *
  * ==Reset domains (spec section 10.1, section 15.1)==
  * Two reset classes share the core clock:
  *
  *  - '''debug-owned''' -- the AXI FSM and every host configuration register. Reset ONLY
  *    by a self-generated power-on pulse. In SpinalHDL that is a derived `ClockDomain`
  *    whose registers are driven from a `resetKind = BOOT` counter, i.e. flops whose only
  *    initial value is the FPGA configuration INIT. This is a direct translation of
  *    `macqd700-soc/cpu/rtl/core/debug/debug_reset_ctl.v`, whose header records the real
  *    2026-07-26 hardware bug it fixes: host-programmed debug configuration was wiped on
  *    every CPU reset, debug reads issued while `cpu_rst` was held were swallowed (a ~10 s
  *    Xicom timeout surfacing as the `0xBADA0BAD` sentinel), and the cold-reset-hold bit
  *    cleared itself because it was both a term of `cpu_rst_or` and reset by `cpu_rst`.
  *    A POR *pulse* rather than bare INIT is required because this domain holds registers
  *    with non-zero reset values (`cpu_ram_window_lg2` = 26, `cpu_mon_sense` = 0x06).
  *
  *  - '''CPU runtime''' -- state that must restart with the CPU. The CPU reset is only ever
  *    OBSERVED, as a rising edge detected inside the debug domain; it is never wired as a
  *    reset here. Stage 1's CPU-coupled runtime state is the sticky init-done latch and the
  *    CONTROL bit 3 override, which that observed edge WIPES (spec section 15.1,
  *    `debug_ctrl.v:2464`). Host configuration -- the cold-reset hold, the RAM window, the
  *    monitor sense -- is the other category and survives the same edge.
  *
  * ==No new `Global` key==
  * Spec section 0.9: "Add no `Global` database key for debug. Parameters are
  * constructor/config values." Stage 1 has no cross-plugin dependency. At Stage 2 this
  * plugin is the sole consumer of `DebugCommitService`, provided by `RobPlugin`; all
  * halt commands and readback cross that service and never reach into ROB internals.
  *
  * @param buildId   SoC-supplied build identity read back at `OFF_BUILD_ID` (spec 3.1:
  *                  "`DBG_BUILD_ID` remains supplied by the SoC build").
  * @param porCycles Length of the debug power-on reset in core clocks.
  * @param stage     Implementation stage this build has actually reached. Drives
  *                  `OFF_FEATURES` through `DebugRegMap.featuresForStage`, which is the
  *                  executable form of spec section 3.4's honesty rule.
  * @param enable    When false, every socket port this plugin declares (`dbg_axi`,
  *                  `cpu_cold_reset_pulse`, `cpu_cold_reset_hold`, `cpu_ram_window_lg2`,
  *                  `cpu_mon_sense`) stays present with its exact name and width -- the
  *                  socket contract never moves -- but is tied to a dead-idle value (AXI
  *                  READY held low forever, `bresp`/`rresp` OKAY on the rare accepted
  *                  default path, cold-reset outputs low, RAM window/mon-sense their POR
  *                  constants) and zero debug logic is instantiated. Mirrors
  *                  `VioProbePlugin`'s `enable` pattern exactly (spec
  *                  `2026-08-18-vio-jtag-debug-design.md` V4). */
class DebugCtrlPlugin(val buildId:   BigInt  = BigInt(0),
                      val porCycles: Int     = DebugRegMap.POR_CYCLES_DEFAULT,
                      val stage:     Int     = 1,
                      val enable:    Boolean = true,
                      val historyDepth: Int  = 32) extends FiberPlugin
                      with m68k040.services.DebugIrqInjectService {
  require(porCycles >= 1, s"DebugCtrlPlugin: porCycles must be >= 1 (got $porCycles)")
  require(stage >= 1, s"DebugCtrlPlugin: stage must be >= 1 (got $stage)")
  require(historyDepth == 0 || historyDepth == 32,
    s"DebugCtrlPlugin: initial forensic history is either disabled or depth 32 (got $historyDepth)")
  require(buildId >= 0 && buildId < (BigInt(1) << DebugRegMap.DBG_DW),
    s"DebugCtrlPlugin: buildId must fit ${DebugRegMap.DBG_DW} bits (got $buildId)")

  // Stage 3 uses exactly one shared integer-PRF read port. It is allocated during
  // setup, as required by RegfileService, and addressed from the captured AXI read
  // offset through the committed-map service. Optional lookup preserves the small
  // standalone CSR fixtures; the shipped Stage-3 full core provides both services.
  private var debugIntRead: RegFileReadPort = null
  private var debugIntWrite: RegFileWritePort = null
  private var debugNzvcWrite: RegFileWritePort = null
  private var debugXWrite: RegFileWritePort = null
  during setup {
    if (enable && stage >= 3) {
      host.get[IntRegFileService].foreach { rf =>
        debugIntRead = rf.newRead(forceNoBypass = true)
        debugIntWrite = rf.newWrite(latency = 1, sharingKey = "excA7", priority = 2)
      }
      host.get[NzvcRegFileService].foreach { rf =>
        debugNzvcWrite = rf.newWrite(latency = 1, sharingKey = "rteNzvc", priority = 2)
      }
      host.get[XRegFileService].foreach { rf =>
        debugXWrite = rf.newWrite(latency = 1, sharingKey = "rteX", priority = 2)
      }
    }
  }

  // Plugin-level handles for the debug-injected interrupt (OFF_IRQ_INJECT). Exposed
  // this way -- the same `var _x` + accessor pattern InterruptControl uses for `iplIn` --
  // rather than reaching into `logic.<...>` from the socket, because `logic` is a
  // `during build` Handle whose structural type does not reliably resolve members.
  var _irqInjectLevel: UInt = null
  var _irqInjectAck:   Bool = null
  override def irqInjectLevel: UInt = _irqInjectLevel
  override def irqInjectAck:   Bool = _irqInjectAck

  val logic = during build new Area {
    // ── Socket surface (cpu_socket.vh section 4) ────────────────────────────────────
    // setName forces the socket's exact Verilog names so macqd700-soc binds them with no
    // rename shim; tools/debug/debug_regmap.def's PORT records are the machine-checked
    // form of this and DebugCtrlPortNamesSpec proves it against the generated netlist.
    val dbgAxi = slave(DbgAxiLite(DebugRegMap.DBG_AW, DebugRegMap.DBG_DW))
    dbgAxi.setName("dbg_axi")

    // ── Socket surface (cpu_socket.vh section 6, "SoC-fabric control group") ────────
    val coldResetPulse = out(Bool()).setName("cpu_cold_reset_pulse")
    val coldResetHold  = out(Bool()).setName("cpu_cold_reset_hold")
    val initDoneSeen   = in(Bool()).setName("init_done_seen")
    coldResetPulse.simPublic(); coldResetHold.simPublic(); initDoneSeen.simPublic()

    val ramWindowLg2 = out(UInt(6 bits)).setName("cpu_ram_window_lg2")
    val monSense     = out(UInt(7 bits)).setName("cpu_mon_sense")
    ramWindowLg2.simPublic(); monSense.simPublic()

    val coreCd = ClockDomain.current

    // ── Debug power-on reset: deliberately reset-LESS flops ─────────────────────────
    // `resetKind = BOOT` means "no reset wire; the init value is the boot value", which
    // synthesises to a Xilinx INIT attribute and simulates from the declaration
    // initializer. debug_reset_ctl.v: "a domain whose purpose is to have no external
    // reset cannot take one."
    // Deliberately UNCONDITIONAL (unlike `CsrArea` below): a tiny (~POR-counter-width)
    // fixed cost kept regardless of `enable` so `porChecks`' required assertion (spec
    // section 13) stays meaningful and `dbgCd`/`porCd` stay in scope for `CsrArea`'s own
    // type definition below -- the real area this plugin is gated for is `CsrArea`'s
    // ~20-register AXI-FSM/CSR set, not this handful of POR-domain flops.
    val porCd = ClockDomain(clock  = coreCd.clock,
                            config = coreCd.config.copy(resetKind = BOOT))
    porCd.setSynchronousWith(coreCd)

    val por = porCd on new Area {
      val cnt  = Reg(UInt(log2Up(porCycles + 1) bits)) init 0
      val done = Reg(Bool()) init False
      when(!done) {
        when(cnt === U(porCycles - 1, cnt.getWidth bits)) { done := True }
          .otherwise                                     { cnt  := cnt + 1 }
      }
    }
    val dbgRst = !por.done
    dbgRst.simPublic()

    // Everything else runs on the core clock with a SYNCHRONOUS reset taken from the
    // debug POR only -- never from the socket `rst` this block is allowed to request.
    val dbgCd = ClockDomain(clock  = coreCd.clock,
                            reset  = dbgRst,
                            config = coreCd.config.copy(resetKind        = SYNC,
                                                        resetActiveLevel = HIGH))
    dbgCd.setSynchronousWith(coreCd)

    // Optional so the Stage-1 standalone fixture remains valid. Stage-2 behavior is
    // compiled only when both the plugin and tranche are enabled; the shipped top stays
    // at Stage 1 until the complete Stage-2 register set is truthful (Task 12).
    val dbgCommit = if (enable && stage >= 2) host.get[DebugCommitService] else None
    val dbgSystem = if (enable && stage >= 3) host.get[DebugSystemStateService] else None
    val committedMap = if (enable && stage >= 3) host.get[CommittedMapService] else None
    val debugMemory = if (enable && stage >= 3) host.get[DebugMemoryService] else None
    val historyEnabled = enable && stage >= 3 && historyDepth > 0
    val debugHistory = if (historyEnabled) host.get[DebugHistoryService] else None
    // OFF_EXC_COUNT source. Deliberately a SEPARATE lookup from `debugHistory`
    // above: the counter must exist whenever there is an exception-entry
    // producer at all, NOT only when the 32-entry forensic ring is built. A
    // build with historyDepth = 0 still has to answer "how many exceptions has
    // this core taken", and `OFF_EXC_RING_HEAD` cannot answer it -- the head is
    // 5 bits and wraps at 32. Reading the head as a count is exactly the
    // mistake this register exists to prevent.
    val excCountHistory = if (enable && stage >= 2) host.get[DebugHistoryService] else None
    val frontendDebug = if (enable && stage >= 5) host.get[FrontendDebugMatchService] else None

    // 2026-09-05 walker-stall observability (p141). `host.get` (Option), not
    // `host[...]`, for exactly the reason the `dbgCommit` comment above gives:
    // the Stage-1 standalone fixture hosts no D-cache, no LS EU, no ROB and no
    // DTLB, and a hard reach-in would make every one of those DUTs fail to
    // elaborate. Each reader below falls back to a literal zero, so an absent
    // peer reads as an all-zero word rather than breaking the build.
    val stallDcache = if (enable) host.get[m68k040.cache.DcachePlugin] else None
    // AxiDMergePlugin shares this PluginHost (SocketTop adds it to M68kCore's plugin
    // list), so the arbiter's post-mortem pack is reachable the same way.
    val stallArb    = if (enable) host.get[m68k040.socket.AxiDMergePlugin] else None
    val stallLsEu   = if (enable) host.get[m68k040.execute.LsEuPlugin] else None
    val stallRob    = if (enable) host.get[m68k040.rob.RobPlugin] else None
    val stallDtlb   = if (enable) host.get[m68k040.mmu.DtlbPlugin] else None
    val stallItlb   = if (enable) host.get[m68k040.mmu.ItlbPlugin] else None
    val historyBuilt = historyEnabled && debugHistory.nonEmpty
    val unavailableFeatures = Set("dcache_probe") ++
      (if (historyBuilt) Set.empty[String] else Set("pc_trace", "exc_ring", "branch_ring")) ++
      (if (debugMemory.nonEmpty) Set.empty[String] else Set("cache_maint_only")) ++
      (if (frontendDebug.nonEmpty) Set.empty[String] else Set("break_pc_multi")) ++
      (if (dbgCommit.nonEmpty) Set.empty[String] else Set("halt_exc_mask"))
    val advertisedFeatures = DebugRegMap.features
      .filter(f => f._3 <= stage && !unavailableFeatures.contains(f._1))
      .foldLeft(BigInt(0))((acc, f) => acc | (BigInt(1) << f._2))

    // A NAMED (not anonymous) local class, so its type stays visible OUTSIDE the
    // `enable` gate below: Stage-1 whitebox tests (`DebugCtrlCsrSpec`, `DebugCtrlResetSpec`,
    // `DebugCtrlSocketSpec`) reach into `dut.dbg.logic.csr.<field>` directly, and Scala
    // does not promote a val's type out of a nested `if` block's local scope (the same
    // constraint `VioProbePlugin`'s own comment documents for its port-facing signals,
    // discovered here to also bite whitebox-only internals reached from OTHER spec files,
    // not just this plugin's own socket ports). `csr: CsrArea` stays a real, stably-typed
    // field of `logic` whether `enable` is true (real object, real hardware: the AXI FSM
    // and every CSR register) or false (null -- zero hardware, this class is never
    // instantiated, so none of its `Reg()` calls ever run).
    class CsrArea extends Area {
      // ── AXI4-Lite slave FSM ───────────────────────────────────────────────────────
      // AW and W are captured INDEPENDENTLY into their own skid registers and the write
      // is applied only once both have arrived (spec 3.1). At most one outstanding read
      // and one outstanding write, and READY is structurally gated by `dbgRst` so no
      // request can be accepted that the response path could not answer.
      val awPend = RegInit(False);                                   awPend.simPublic()
      val awAddr = Reg(UInt(DebugRegMap.DBG_AW bits)) init 0;        awAddr.simPublic()
      val wPend  = RegInit(False);                                   wPend.simPublic()
      val wData  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0;        wData.simPublic()
      val wStrb  = Reg(Bits(DebugRegMap.DBG_DW / 8 bits)) init 0;    wStrb.simPublic()
      val bPend  = RegInit(False);                                   bPend.simPublic()
      val arPend = RegInit(False);                                   arPend.simPublic()
      val arAddr = Reg(UInt(DebugRegMap.DBG_AW bits)) init 0;        arAddr.simPublic()
      val rPend  = RegInit(False);                                   rPend.simPublic()
      val rData  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0;        rData.simPublic()

      // ── 32-entry committed forensic histories ───────────────────────────────
      // PC storage is split even/odd so a dual-macro retire writes each synchronous
      // memory at most once while preserving program order. Branch and exception
      // families are architecturally single-event-per-cycle and use one 128-bit word.
      val pcTraceHead = if (historyBuilt) Reg(UInt(5 bits)) init 0 else U(0, 5 bits)
      val branchRingHead = if (historyBuilt) Reg(UInt(5 bits)) init 0 else U(0, 5 bits)
      val excRingHead = if (historyBuilt) Reg(UInt(5 bits)) init 0 else U(0, 5 bits)
      val pcTraceEven = if (historyBuilt)
        (Mem(Bits(32 bits), 16) init Vector.fill(16)(B(0, 32 bits))) else null
      val pcTraceOdd = if (historyBuilt)
        (Mem(Bits(32 bits), 16) init Vector.fill(16)(B(0, 32 bits))) else null
      val branchRing = if (historyBuilt)
        (Mem(Bits(128 bits), 32) init Vector.fill(32)(B(0, 128 bits))) else null
      val excRing = if (historyBuilt)
        (Mem(Bits(128 bits), 32) init Vector.fill(32)(B(0, 128 bits))) else null
      if (historyBuilt) {
        pcTraceEven.addAttribute("ram_style", "block")
        pcTraceOdd.addAttribute("ram_style", "block")
        branchRing.addAttribute("ram_style", "block")
        excRing.addAttribute("ram_style", "block")

        val h = debugHistory.get
        val pc0 = h.macroRetirePc(0)
        val pc1 = h.macroRetirePc(1)
        val pcCount = pc0.valid.asUInt.resize(2) + pc1.valid.asUInt.resize(2)
        val firstPc = Mux(pc0.valid, pc0.payload, pc1.payload).asBits
        val secondPc = pc1.payload.asBits
        val nextPcIndex = (pcTraceHead + 1).resized
        val evenWrite = Bool(); val oddWrite = Bool()
        val evenAddr = UInt(4 bits); val oddAddr = UInt(4 bits)
        val evenData = Bits(32 bits); val oddData = Bits(32 bits)
        evenWrite := False; oddWrite := False
        evenAddr := pcTraceHead(4 downto 1); oddAddr := pcTraceHead(4 downto 1)
        evenData := firstPc; oddData := firstPc
        when(pcCount === 1) {
          when(pcTraceHead(0)) { oddWrite := True }.otherwise { evenWrite := True }
        }.elsewhen(pcCount === 2) {
          when(pcTraceHead(0)) {
            oddWrite := True; oddData := firstPc
            evenWrite := True; evenAddr := nextPcIndex(4 downto 1); evenData := secondPc
          }.otherwise {
            evenWrite := True; evenData := firstPc
            oddWrite := True; oddAddr := nextPcIndex(4 downto 1); oddData := secondPc
          }
        }
        pcTraceEven.write(evenAddr, evenData, evenWrite)
        pcTraceOdd.write(oddAddr, oddData, oddWrite)
        when(pcCount =/= 0) { pcTraceHead := pcTraceHead + pcCount.resized }

        val br = h.branchRetire
        val brMeta = B(0, 28 bits) ## br.payload.branchType.asBits ##
          br.payload.mispredicted ## br.payload.taken
        branchRing.write(branchRingHead,
          B(0, 32 bits) ## brMeta ## br.payload.nextPc.asBits ## br.payload.pc.asBits,
          br.valid)
        when(br.valid) { branchRingHead := branchRingHead + 1 }

        val ex = h.exceptionEntry
        val exMeta = B(0, 24 bits) ## ex.payload.vector.asBits
        excRing.write(excRingHead,
          ex.payload.handlerPc.asBits ## ex.payload.faultAddress.asBits ##
            ex.payload.exceptionPc.asBits ## exMeta,
          ex.valid)
        when(ex.valid) { excRingHead := excRingHead + 1 }
      }

      // ── Free-running exception counter (OFF_EXC_COUNT) ───────────────────────
      // Counts EVERY exception ENTRY the ROB commits -- the same `exceptionEntry`
      // pulse the forensic ring records, unfiltered by vector, by the halt mask,
      // or by whether the ring is even built. Mirrors v1's `exc_count_r`
      // (macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:2859), including its
      // lifetime: it clears on CPU reset and NOTHING else. It is deliberately
      // NOT halt-captured and NOT clearable by the sticky-clear control bit, so
      // it can be sampled twice under free-run to get an exception RATE.
      //
      // 32 bits, matching v1 and the register width. At the highest rate ever
      // measured on this SoC (154 exceptions/s, the 60 Hz VIA tick plus SCSI)
      // that wraps in ~885 years; even a pathological 1-per-100-cycles at
      // 200 MHz takes ~35 min. Wrap is not a practical concern -- which is the
      // whole point, since the 5-bit ring head wrapping at 32 is what produced a
      // wrong conclusion before this register was served.
      val excCountReg = excCountHistory.map(_ => Reg(UInt(32 bits)) init 0)
      excCountReg.foreach(_.simPublic())
      excCountHistory.foreach { h =>
        when(h.exceptionEntry.valid) { excCountReg.get := excCountReg.get + 1 }
      }
      def excCount: Bits = excCountReg.map(_.asBits).getOrElse(B(0, 32 bits))

      val pcBodyRead = if (historyBuilt)
        arAddr >= DebugRegMap.OFF_PC_TRACE_BODY &&
          arAddr < DebugRegMap.OFF_PC_TRACE_BODY + historyDepth * 4 else False
      val excBodyRead = if (historyBuilt)
        arAddr >= DebugRegMap.OFF_EXC_RING_BODY &&
          arAddr < DebugRegMap.OFF_EXC_RING_BODY + historyDepth * 16 else False
      val branchBodyRead = if (historyBuilt)
        arAddr >= DebugRegMap.OFF_BRANCH_RING_BODY &&
          arAddr < DebugRegMap.OFF_BRANCH_RING_BODY + historyDepth * 16 else False
      val historyBodyRead = pcBodyRead || excBodyRead || branchBodyRead
      val historyReadIssue = Bool()
      val historyReadPending = RegNext(historyReadIssue) init False
      val historyReadKind = if (historyBuilt) Reg(UInt(2 bits)) init 0 else U(0, 2 bits)
      val historyReadWord = if (historyBuilt) Reg(UInt(2 bits)) init 0 else U(0, 2 bits)
      val historyReadPcOdd = if (historyBuilt) RegInit(False) else False
      val pcReadIndex = ((arAddr - DebugRegMap.OFF_PC_TRACE_BODY) >> 2).resize(5)
      val excReadIndex = ((arAddr - DebugRegMap.OFF_EXC_RING_BODY) >> 4).resize(5)
      val branchReadIndex = ((arAddr - DebugRegMap.OFF_BRANCH_RING_BODY) >> 4).resize(5)
      val pcEvenRead = if (historyBuilt)
        pcTraceEven.readSync(pcReadIndex(4 downto 1), historyReadIssue && pcBodyRead && !pcReadIndex(0))
        else B(0, 32 bits)
      val pcOddRead = if (historyBuilt)
        pcTraceOdd.readSync(pcReadIndex(4 downto 1), historyReadIssue && pcBodyRead && pcReadIndex(0))
        else B(0, 32 bits)
      val excBodyWord = if (historyBuilt)
        excRing.readSync(excReadIndex, historyReadIssue && excBodyRead) else B(0, 128 bits)
      val branchBodyWord = if (historyBuilt)
        branchRing.readSync(branchReadIndex, historyReadIssue && branchBodyRead) else B(0, 128 bits)

      dbgAxi.awready := !awPend && !bPend && !dbgRst
      dbgAxi.wready  := !wPend  && !bPend && !dbgRst
      dbgAxi.arready := !arPend && !rPend && !historyReadPending && !dbgRst
      dbgAxi.bvalid  := bPend
      dbgAxi.bresp   := DbgAxiLite.RESP_OKAY
      dbgAxi.rvalid  := rPend
      dbgAxi.rdata   := rData
      dbgAxi.rresp   := DbgAxiLite.RESP_OKAY

      when(dbgAxi.awvalid && dbgAxi.awready) { awPend := True; awAddr := dbgAxi.awaddr }
      when(dbgAxi.wvalid  && dbgAxi.wready)  {
        wPend := True; wData := dbgAxi.wdata; wStrb := dbgAxi.wstrb
      }
      /** High for exactly one cycle, the cycle in which the captured write is applied. */
      val doWrite = awPend && wPend && !bPend
      doWrite.simPublic()
      when(doWrite)                 { awPend := False; wPend := False; bPend := True }
      when(bPend && dbgAxi.bready)  { bPend := False }

      // ── OFF_IRQ_INJECT (0x020): debug-driven interrupt request ────────────────
      // `DebugRegMap` has carried `OFF_IRQ_INJECT` since the map was written, and
      // `tools/jtag_repl.tcl` has shipped an `irq-inject <level> [count] [delay_ms]`
      // command that writes it -- but NOTHING in this plugin ever decoded the
      // address. The write was ACCEPTED and silently discarded, so JTAG interrupt
      // and NMI injection has never done anything. Found 2026-09-11 after the owner
      // reported "nmi injection seems broken".
      //
      // (The HARDWARE NMI path is separate and intact: btn[1] -> debounce ->
      // nmi_btn_core -> irq_agg.nmi_edge -> cpu_ipl, with ipl_ack returned by
      // IplAckPlugin. Only the JTAG route was missing.)
      //
      // Semantics deliberately match a real device rather than a one-shot pulse:
      // the requested level is HELD until the CPU actually TAKES an interrupt entry
      // (`irqInjectAck`, driven from the socket's `ipl_ack`), so a request cannot be
      // missed because it happened to land while the core was masked or mid-flush.
      // Writing level 0 cancels a pending request. Level 7 is the NMI.
      val irqInjectLevel = RegInit(U(0, 3 bits)); irqInjectLevel.simPublic()
      val irqInjectAck   = Bool(); irqInjectAck.allowOverride; irqInjectAck := False
      irqInjectAck.simPublic()
      // Clear first, write second: a write landing on the same cycle as an ack is a
      // NEW request and must win, or a back-to-back inject would be swallowed.
      when(irqInjectAck) { irqInjectLevel := 0 }
      when(doWrite && awAddr === DebugRegMap.OFF_IRQ_INJECT && wStrb(0)) {
        irqInjectLevel := wData(2 downto 0).asUInt
      }
      _irqInjectLevel = irqInjectLevel
      _irqInjectAck   = irqInjectAck

      when(dbgAxi.arvalid && dbgAxi.arready) { arPend := True; arAddr := dbgAxi.araddr }
      /** High for exactly one cycle, the cycle in which the read mux result is latched.
        * Registering the result is deliberate: spec section 11 rule 1 keeps the CSR read
        * mux off any combinational path leaving this block, and spec 3.1 explicitly frees
        * the implementation from the legacy fixed two-cycle latency. */
      val doRead = arPend && !rPend
      doRead.simPublic()
      historyReadIssue := doRead && historyBodyRead
      when(doRead) {
        arPend := False
        when(!historyBodyRead) { rPend := True }
        if (historyBuilt) when(historyBodyRead) {
          historyReadKind := Mux(pcBodyRead, U(0, 2 bits), Mux(excBodyRead, U(1, 2 bits), U(2, 2 bits)))
          historyReadWord := arAddr(3 downto 2)
          historyReadPcOdd := pcReadIndex(0)
        }
      }
      when(historyReadPending) {
        rPend := True
        switch(historyReadKind) {
          is(U(0, 2 bits)) { rData := Mux(historyReadPcOdd, pcOddRead, pcEvenRead) }
          is(U(1, 2 bits)) { rData := excBodyWord.subdivideIn(32 bits)(historyReadWord) }
          is(U(2, 2 bits)) { rData := branchBodyWord.subdivideIn(32 bits)(historyReadWord) }
        }
      }
      when(rPend && dbgAxi.rready)  { rPend := False }

      /** Apply the captured byte strobes to `cur` (spec 3.1: "honor `WSTRB` per byte for
        * ordinary RW fields"). Bytes whose strobe is 0 keep their previous value. */
      def merged(cur: Bits): Bits = {
        val out = Bits(DebugRegMap.DBG_DW bits)
        for (i <- 0 until DebugRegMap.DBG_DW / 8)
          out(i * 8 + 7 downto i * 8) := Mux(wStrb(i),
                                             wData(i * 8 + 7 downto i * 8),
                                             cur(i * 8 + 7 downto i * 8))
        out
      }

      // ── CPU reset: OBSERVED, never consumed ────────────────────────────────────
      // The socket `rst` is read as data by registers that this reset does not clear.
      // Reset value True so a `rst` already high when the debug domain leaves POR does
      // not manufacture a spurious edge (debug_reset_ctl.v:133-142).
      // `CombInit` is LOAD-BEARING, not a style choice. For an async-reset ClockDomain,
      // `coreCd.isResetActive` IS the core's reset wire itself, so binding it to a named
      // val inside this Area makes SpinalHDL rename the WHOLE CORE's top-level `reset`
      // port to `DebugCtrlPlugin_logic_csr_cpuRstLevel` -- a whole-core interface break
      // that violates spec 15.1's "socket names export verbatim, no rename shim".
      // `CombInit` creates a genuinely fresh signal that merely copies the value.
      val cpuRstLevel = CombInit(coreCd.isResetActive)
      val cpuRstQ     = RegInit(True)
      cpuRstQ := cpuRstLevel
      val cpuRstEvent = cpuRstLevel && !cpuRstQ; cpuRstEvent.simPublic()

      /** Surviving 16-bit count of observed CPU-reset edges. SATURATES: zero means "no
        * reset observed since the last clear" and must not be reachable by wraparound
        * (spec 15.4). Lives in the debug domain, so it survives the resets it counts.
        *
        * The INCREMENT is deliberately NOT written here: it lives below the write decode,
        * in the `when(cpuRstEvent)` block, so that last-assignment-wins gives a genuine
        * reset edge priority over a host bit-1 clear landing on the very same cycle. The
        * deployed reference has exactly that ordering -- its `cpu_reset_count_r <= 16'd0`
        * write arm is at debug_ctrl.v:2417 and the saturating increment at :2709, later in
        * the same always block, so the increment wins the collision there too. Losing the
        * edge instead would tell the host "no reset occurred" about a reset that did. */
      val cpuResetCount = Reg(UInt(16 bits)) init 0; cpuResetCount.simPublic()

      // ── Host configuration: survives every CPU reset (spec 15.1) ─────────────────
      // The hold is a term of the SoC's cpu_rst_or AND lives in a domain that reset does
      // not clear: the exact pairing debug_reset_ctl.v exists to make possible, since a
      // hold that cleared itself on the reset it requests can never hold anything.
      val ctrlColdHold    = RegInit(False); ctrlColdHold.simPublic()

      /** One-cycle pulse; defaults low every cycle and is set only by a CONTROL write.
        * Self-clearing, so the survives/wiped question does not arise for it. */
      val coldPulse       = RegInit(False); coldPulse.simPublic()
      coldPulse := False

      // ── CPU-COUPLED RUNTIME state: WIPED on the CPU-reset edge (spec 15.1) ───────
      // Not host configuration. State that, if it survived, "would report a previous life
      // as the current one": STATUS bit 2 is a claim that DDR calibration completed, and a
      // debugger attaching after a CPU reset must not be handed the previous boot's claim
      // (nor the previous host's CONTROL-bit-3 forgery of it). The deployed reference
      // wipes both of these in its cpu_rst_event-gated counters_clear block
      // (debug_ctrl.v:2464, :2468, :2561). Living in the debug reset domain does NOT by
      // itself mean surviving CPU reset -- the edge detector above is precisely what lets
      // same-domain logic tell the two categories apart.
      // The wipe itself is deliberately NOT written here; see the block after the write
      // decode below, and the ordering note there for why.
      val ctrlInitDoneOvr = RegInit(False); ctrlInitDoneOvr.simPublic()
      val initDoneSticky  = RegInit(False); initDoneSticky.simPublic()

      val initDoneLatched = initDoneSticky || ctrlInitDoneOvr

      // Manual halt is retained as a level solely for CONTROL readback compatibility.
      // The ROB receives registered one-cycle COMMANDS. These pulses are generated by
      // every accepted byte-0 CONTROL write, rather than by level edge detection: a
      // write of zero must resume an automatic halt even when this stored level was
      // already zero (spec section 6.4).
      val manualHaltLevel = if (stage >= 2) RegInit(False) else False
      val debugStopRequest = if (stage >= 2) RegInit(False) else False
      val debugResumeRequest = if (stage >= 2) RegInit(False) else False
      val debugStepRequest = if (stage >= 2) RegInit(False) else False
      val debugClearStickyRequest = if (stage >= 2) RegInit(False) else False
      if (stage >= 2) {
        manualHaltLevel.simPublic()
        debugStopRequest.simPublic(); debugResumeRequest.simPublic(); debugStepRequest.simPublic()
        debugClearStickyRequest.simPublic()
        debugStopRequest := False
        debugResumeRequest := False
        debugStepRequest := False
        debugClearStickyRequest := False
      }

      // Halt-after configuration is debug-owned and therefore survives CPU reset.
      // A target write disarms and advances the epoch; OFF_HALT_CTL bit 0 explicitly
      // arms the complete 64-bit value. The ROB consumes an automatic hit once.
      val haltAfterTarget = if (stage >= 2) Reg(UInt(64 bits)) init 0 else U(0, 64 bits)
      val haltAfterEpoch = if (stage >= 2) Reg(UInt(8 bits)) init 0 else U(0, 8 bits)
      val haltAfterArmed = if (stage >= 2) RegInit(False) else False
      if (stage >= 2) {
        haltAfterTarget.simPublic(); haltAfterEpoch.simPublic(); haltAfterArmed.simPublic()
      }
      val haltAfterInvalidate = if (stage >= 2) {
        doWrite && wStrb.orR &&
          ((awAddr === DebugRegMap.OFF_HALT_AFTER_LO) ||
           (awAddr === DebugRegMap.OFF_HALT_AFTER_HI))
      } else False
      if (stage >= 2) haltAfterInvalidate.simPublic()

      // Stage-5 breakpoint/exception configuration lives in the debug reset domain and
      // therefore survives CPU reset. Runtime hit descriptors remain ROB-owned.
      val breakPc = if (stage >= 5) Vec.fill(4)(Reg(UInt(32 bits)) init 0) else null
      val breakPcEnable = if (stage >= 5) Reg(Bits(4 bits)) init 0 else B(0, 4 bits)
      val breakSkipOnce = if (stage >= 5) Reg(Bits(4 bits)) init 0 else B(0, 4 bits)
      val haltExceptionMask = if (stage >= 5)
        Vec.fill(8)(Reg(Bits(32 bits)) init 0) else null
      // A7-ODD halt lane control (bit 0 enable, bits 31:16 threshold); debug reset
      // domain like the masks, so it survives the CPU resets a boot trial issues.
      val a7OddCtl = if (stage >= 5) Reg(Bits(32 bits)) init 0 else null
      val pcRangeCtl = if (stage >= 5) Reg(Bits(32 bits)) init 0 else null
      val pcRangeLo  = if (stage >= 5) Reg(Bits(32 bits)) init 0 else null
      val pcRangeHi  = if (stage >= 5) Reg(Bits(32 bits)) init 0 else null
      if (stage >= 5) {
        breakPc.simPublic(); breakPcEnable.simPublic(); breakSkipOnce.simPublic()
        haltExceptionMask.simPublic(); a7OddCtl.simPublic()
        pcRangeCtl.simPublic(); pcRangeLo.simPublic(); pcRangeHi.simPublic()
        frontendDebug.foreach { matcher =>
          when(matcher.skipConsumed.orR) {
            breakSkipOnce := breakSkipOnce & ~matcher.skipConsumed
          }
        }
        dbgCommit.foreach { commit =>
          // Arming wins over a same-cycle consume; a newly reported breakpoint must be
          // skippable before the following cycle can expose HALTED.
          when(commit.breakpointHit.valid) {
            breakSkipOnce(commit.breakpointHit.payload) := True
          }
        }
      }

      // ── Stage 3 architectural write shadows ─────────────────────────────────
      // One word/dirty bit per independently applicable field. Logical indices are
      // deliberately decoupled from CSR word offsets because APPLY/STATUS occupy two
      // holes before SFC/DFC in the frozen deployed map.
      val archShadow = if (stage >= 3) Vec.fill(32)(Reg(Bits(32 bits)) init 0) else null
      val archDirty  = if (stage >= 3) Reg(Bits(32 bits)) init 0 else null
      if (stage >= 3) { archShadow.simPublic(); archDirty.simPublic() }
      val archWriteMask = if (stage >= 3) Bits(32 bits) else null
      if (stage >= 3) archWriteMask := B(0, 32 bits)
      def archWord(index: Int): Bits = if (stage >= 3) archShadow(index) else B(0, 32 bits)
      def writeArch(index: Int): Unit = if (stage >= 3) when(wStrb.orR) {
        archShadow(index) := merged(archShadow(index))
        archDirty(index) := True
        archWriteMask(index) := True
      }

      // Halted architectural apply. The transaction snapshots both values and dirty
      // bits, then uses one shared direct-write port per renamed register class. A host
      // may continue staging the next transaction while this one is busy; rewriteMask
      // preserves those later dirty bits when the snapshotted transaction completes.
      val archApplyBusy = if (stage >= 3) RegInit(False) else False
      val archApplyDone = if (stage >= 3) RegInit(False) else False
      val archApplyRejected = if (stage >= 3) RegInit(False) else False
      val archApplyPhase = if (stage >= 3) Reg(UInt(2 bits)) init 0 else U(0, 2 bits)
      val archApplyIndex = if (stage >= 3) Reg(UInt(4 bits)) init 0 else U(0, 4 bits)
      val archApplyDirty = if (stage >= 3) Reg(Bits(32 bits)) init 0 else null
      val archApplyRewrite = if (stage >= 3) Reg(Bits(32 bits)) init 0 else null
      val archApplyShadow = if (stage >= 3) Vec.fill(32)(Reg(Bits(32 bits)) init 0) else null
      val archApplyComplete = if (stage >= 3) Bool() else False
      if (stage >= 3) {
        archApplyBusy.simPublic(); archApplyDone.simPublic(); archApplyRejected.simPublic()
        archApplyDirty.simPublic(); archApplyComplete := False
      }

      val systemApply = if (stage >= 3) spinal.lib.Flow(m68k040.services.DebugSystemApply()) else null
      val debugMaintCmd = if (stage >= 3) spinal.lib.Flow(DebugMemoryCommand()) else null
      val cacheOpBusy = if (stage >= 4) RegInit(False) else False
      val cacheOpDone = if (stage >= 4) RegInit(False) else False
      val cacheOpRejected = if (stage >= 4) RegInit(False) else False
      val cacheOpError = if (stage >= 4) RegInit(False) else False
      val cacheOpLaunched = if (stage >= 4) RegInit(False) else False
      val cacheOpSel = if (stage >= 4) Reg(UInt(2 bits)) init 0 else U(0, 2 bits)
      val cacheOpPush = if (stage >= 4) RegInit(False) else False
      val cacheOpInvalidate = if (stage >= 4) RegInit(False) else False
      if (stage >= 3) {
        debugMaintCmd.valid := False
        debugMaintCmd.payload.push := False
        debugMaintCmd.payload.invalidate := False
        debugMaintCmd.payload.sel := 0
        systemApply.valid := False
        systemApply.payload.srValid := False; systemApply.payload.pcValid := False
        systemApply.payload.vbrValid := False; systemApply.payload.uspValid := False
        systemApply.payload.mspValid := False; systemApply.payload.ispValid := False
        systemApply.payload.cacrValid := False; systemApply.payload.sfcValid := False
        systemApply.payload.dfcValid := False; systemApply.payload.tcValid := False
        systemApply.payload.itt0Valid := False; systemApply.payload.itt1Valid := False
        systemApply.payload.dtt0Valid := False; systemApply.payload.dtt1Valid := False
        systemApply.payload.urpValid := False; systemApply.payload.srpValid := False
        systemApply.payload.sr := 0; systemApply.payload.pc := 0; systemApply.payload.vbr := 0
        systemApply.payload.usp := 0; systemApply.payload.msp := 0; systemApply.payload.isp := 0
        systemApply.payload.cacr := 0; systemApply.payload.sfc := 0; systemApply.payload.dfc := 0
        systemApply.payload.tc := 0; systemApply.payload.itt0 := 0; systemApply.payload.itt1 := 0
        systemApply.payload.dtt0 := 0; systemApply.payload.dtt1 := 0
        systemApply.payload.urp := 0; systemApply.payload.srp := 0
      }

      if (debugIntWrite != null) {
        debugIntWrite.valid := False; debugIntWrite.address := 0; debugIntWrite.data := 0
      }
      if (debugNzvcWrite != null) {
        debugNzvcWrite.valid := False; debugNzvcWrite.address := 0; debugNzvcWrite.data := 0
      }
      if (debugXWrite != null) {
        debugXWrite.valid := False; debugXWrite.address := 0; debugXWrite.data := 0
      }

      def effectiveHalt: Bool =
        if (stage >= 2) dbgCommit.map(_.effectiveHalt).getOrElse(False) else False
      def automaticHalt: Bool =
        if (stage >= 2) dbgCommit.map(_.autoHaltLatched).getOrElse(False) else False
      def livePc: UInt =
        if (stage >= 2) dbgCommit.map(_.livePc).getOrElse(U(0, 32 bits)) else U(0, 32 bits)

      val liveIntArch = UInt(4 bits)
      liveIntArch := 0
      when(arAddr >= DebugRegMap.OFF_LIVE_DREG0 && arAddr <= DebugRegMap.OFF_LIVE_DREG7) {
        liveIntArch := ((arAddr - DebugRegMap.OFF_LIVE_DREG0) >> 2).resized
      }
      when(arAddr >= DebugRegMap.OFF_LIVE_AREG0 && arAddr <= DebugRegMap.OFF_LIVE_AREG7) {
        liveIntArch := (((arAddr - DebugRegMap.OFF_LIVE_AREG0) >> 2) + 8).resized
      }
      when(arAddr === DebugRegMap.OFF_LIVE_A7) { liveIntArch := 15 }
      if (debugIntRead != null) {
        debugIntRead.addr := committedMap.map { map =>
          map.intPhys(liveIntArch.resize(log2Up(map.intPhys.length))).resized
        }
          .getOrElse(U(0, debugIntRead.addr.getWidth bits))
      }
      def liveIntWord: Bits =
        if (debugIntRead != null) debugIntRead.data else B(0, 32 bits)
      def lastPc: UInt =
        if (stage >= 2) dbgCommit.map(_.lastPc).getOrElse(U(0, 32 bits)) else U(0, 32 bits)
      def macroCount: UInt =
        if (stage >= 2) dbgCommit.map(_.macroCount).getOrElse(U(0, 64 bits)) else U(0, 64 bits)
      def haltHitInstCount: UInt =
        if (stage >= 2) dbgCommit.map(_.haltHitInstCount).getOrElse(U(0, 64 bits))
        else U(0, 64 bits)
      def haltAfterConsumed: Bool =
        if (stage >= 2) dbgCommit.map(_.haltAfterConsumed).getOrElse(False) else False

      if (stage >= 4) {
        cacheOpBusy.simPublic(); cacheOpDone.simPublic(); cacheOpRejected.simPublic()
        cacheOpError.simPublic(); cacheOpLaunched.simPublic(); cacheOpSel.simPublic()
        val dcacheStart = doWrite && awAddr === DebugRegMap.OFF_DCACHE_OP &&
          wStrb(0) && wData(0)
        val icacheStart = doWrite && awAddr === DebugRegMap.OFF_ICACHE_OP &&
          wStrb(0) && wData(0)
        val cacheStart = dcacheStart || icacheStart
        val memoryCapable = Bool(debugMemory.nonEmpty)
        val memoryQuiesced = debugMemory.map(_.quiesced).getOrElse(False)
        val memoryDone = debugMemory.map(_.done).getOrElse(False)
        val memoryError = debugMemory.map(_.error).getOrElse(False)

        when(cacheStart) {
          cacheOpDone := False
          cacheOpRejected := False
          cacheOpError := False
          when(cacheOpBusy || archApplyBusy || !effectiveHalt || !memoryCapable) {
            cacheOpRejected := True
          }.otherwise {
            cacheOpBusy := True
            cacheOpLaunched := False
            cacheOpSel := Mux(dcacheStart, U(1, 2 bits), U(2, 2 bits))
            cacheOpPush := dcacheStart && wData(1)
            cacheOpInvalidate := icacheStart || (dcacheStart && !wData(1))
          }
        }.elsewhen(cacheOpBusy) {
          when(!effectiveHalt) {
            cacheOpBusy := False
            cacheOpLaunched := False
            cacheOpRejected := True
          }.elsewhen(!cacheOpLaunched && memoryQuiesced) {
            debugMaintCmd.valid := True
            debugMaintCmd.payload.push := cacheOpPush
            debugMaintCmd.payload.invalidate := cacheOpInvalidate
            debugMaintCmd.payload.sel := cacheOpSel
            cacheOpLaunched := True
          }.elsewhen(cacheOpLaunched && memoryDone) {
            cacheOpBusy := False
            cacheOpLaunched := False
            cacheOpDone := True
            cacheOpError := memoryError
          }
        }
      }

      if (stage >= 3) {
        val applyStart = doWrite && (awAddr === DebugRegMap.OFF_ARCH_APPLY) &&
          wStrb(0) && wData(0)
        val applyClear = doWrite && (awAddr === DebugRegMap.OFF_ARCH_APPLY) &&
          wStrb(0) && wData(1)
        val applyCapable = Bool(debugIntWrite != null && debugNzvcWrite != null &&
          debugXWrite != null && committedMap.nonEmpty && dbgSystem.nonEmpty)
        val memoryQuiesced = debugMemory.map(_.quiesced).getOrElse(True)
        val memoryMaintDone = debugMemory.map(_.done).getOrElse(False)
        val requestedNeedsMemoryService = archDirty(21) && !Bool(debugMemory.nonEmpty)

        when(applyClear) {
          archApplyDone := False
          archApplyRejected := False
        }
        when(applyStart) {
          archApplyDone := False
          when(archApplyBusy || cacheOpBusy || !effectiveHalt || !applyCapable || requestedNeedsMemoryService) {
            archApplyRejected := True
          }.otherwise {
            archApplyBusy := True
            archApplyRejected := False
            archApplyPhase := 0
            archApplyIndex := 0
            archApplyDirty := archDirty
            archApplyRewrite := 0
            for (i <- 0 until 32) archApplyShadow(i) := archShadow(i)
          }
        }.elsewhen(archApplyBusy) {
          when(!effectiveHalt) {
            // Defensive domain-startup/resume-race guard: no direct architectural
            // write is ever allowed without the commit owner's live halt grant.
            archApplyBusy := False
            archApplyRejected := True
          }.otherwise {
            when(archWriteMask.orR) { archApplyRewrite := archApplyRewrite | archWriteMask }
            switch(archApplyPhase) {
            is(U(0, 2 bits)) {
              when(memoryQuiesced) {
                when(archApplyDirty(21)) {
                  debugMaintCmd.valid := True
                  debugMaintCmd.payload.push := True
                  debugMaintCmd.payload.invalidate := True
                  debugMaintCmd.payload.sel := 3
                  archApplyPhase := 3
                }.otherwise {
                  archApplyPhase := 1
                }
              }
            }
            is(U(1, 2 bits)) {
              if (debugIntWrite != null) {
                debugIntWrite.valid := archApplyDirty(archApplyIndex)
                debugIntWrite.address := committedMap.get.intPhys(
                  archApplyIndex.resize(log2Up(committedMap.get.intPhys.length))).resized
                debugIntWrite.data := archApplyShadow(archApplyIndex.resize(5))
              }
              when(archApplyIndex === 15) { archApplyPhase := 2 }
                .otherwise { archApplyIndex := archApplyIndex + 1 }
            }
            is(U(2, 2 bits)) {
              systemApply.valid := True
              systemApply.payload.uspValid := archApplyDirty(16)
              systemApply.payload.mspValid := archApplyDirty(17)
              systemApply.payload.ispValid := archApplyDirty(18)
              systemApply.payload.srValid := archApplyDirty(19)
              systemApply.payload.vbrValid := archApplyDirty(20)
              systemApply.payload.cacrValid := archApplyDirty(21)
              systemApply.payload.tcValid := archApplyDirty(22)
              systemApply.payload.itt0Valid := archApplyDirty(23)
              systemApply.payload.itt1Valid := archApplyDirty(24)
              systemApply.payload.dtt0Valid := archApplyDirty(25)
              systemApply.payload.dtt1Valid := archApplyDirty(26)
              systemApply.payload.urpValid := archApplyDirty(27)
              systemApply.payload.srpValid := archApplyDirty(28)
              systemApply.payload.pcValid := archApplyDirty(29)
              systemApply.payload.sfcValid := archApplyDirty(30)
              systemApply.payload.dfcValid := archApplyDirty(31)
              systemApply.payload.usp := archApplyShadow(16).asUInt
              systemApply.payload.msp := archApplyShadow(17).asUInt
              systemApply.payload.isp := archApplyShadow(18).asUInt
              systemApply.payload.sr := archApplyShadow(19)(15 downto 0).asUInt
              systemApply.payload.vbr := archApplyShadow(20).asUInt
              systemApply.payload.cacr := archApplyShadow(21).asUInt
              systemApply.payload.tc := archApplyShadow(22).asUInt
              systemApply.payload.itt0 := archApplyShadow(23).asUInt
              systemApply.payload.itt1 := archApplyShadow(24).asUInt
              systemApply.payload.dtt0 := archApplyShadow(25).asUInt
              systemApply.payload.dtt1 := archApplyShadow(26).asUInt
              systemApply.payload.urp := archApplyShadow(27).asUInt
              systemApply.payload.srp := archApplyShadow(28).asUInt
              systemApply.payload.pc := archApplyShadow(29).asUInt
              systemApply.payload.sfc := archApplyShadow(30)(2 downto 0).asUInt
              systemApply.payload.dfc := archApplyShadow(31)(2 downto 0).asUInt
              if (debugNzvcWrite != null) {
                debugNzvcWrite.valid := archApplyDirty(19)
                debugNzvcWrite.address := committedMap.get.nzvcPhys.resized
                debugNzvcWrite.data := archApplyShadow(19)(3 downto 0)
              }
              if (debugXWrite != null) {
                debugXWrite.valid := archApplyDirty(19)
                debugXWrite.address := committedMap.get.xPhys.resized
                debugXWrite.data := archApplyShadow(19)(4 downto 4)
              }
              archApplyBusy := False
              archApplyDone := True
              archApplyComplete := True
            }
            is(U(3, 2 bits)) {
              when(memoryMaintDone) {
                when(debugMemory.map(_.error).getOrElse(False)) {
                  archApplyBusy := False
                  archApplyRejected := True
                }.otherwise {
                  archApplyPhase := 1
                }
              }
            }
            }
          }
        }
      }

      /** The CONTROL word exactly as the host reads it back. Defined ONCE and used by
        * both the read mux and the write-side byte-strobe merge, so the two can never
        * disagree about a bit's position or its RAZ/WI status. */
      def controlWord: Bits =
        B(0, 24 bits) ##
        False ##            // bit 7  legacy step-arm observation -- retained RAZ/WI
        False ##            // bit 6  reserved             -- always 0 (spec 3.3)
        coldPulse ##        // bit 5  cold-reset pulse
        ctrlColdHold ##     // bit 4  cold-reset hold level
        ctrlInitDoneOvr ##  // bit 3  init-done override level
        coldPulse ##        // bit 2  deprecated pulse alias of bit 5
        debugStepRequest ## // bit 1  single-step pulse (self-clearing command)
        manualHaltLevel     // bit 0  manual halt request level (RAZ/WI in Stage 1)

      // ── SoC-fabric configuration, debug reset domain ────────────────────────────
      // POR values and the clamp bounds come from the deployed controller
      // (debug_ctrl.v:1805 ram_window_lg2_r <= 6'd26, :1814 mon_sense_r <= 7'h06,
      // :2202-2206 the [22,30] write clamp) via debug_regmap.def, never restated here.
      val ramWindow = Reg(UInt(6 bits)) init U(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits)
      val mon       = Reg(UInt(7 bits)) init U(DebugRegMap.MON_SENSE_POR, 7 bits)
      ramWindow.simPublic(); mon.simPublic()

      /** One-cycle strobe raised by OFF_DBG_RESET_CTL bit 0. Deliberately NOT a reset:
        * it leaves the AXI FSM alone so the requesting write still gets its B response
        * (spec 15.3; debug_ctrl.v:3016-3024). */
      val cfgWipe = RegInit(False); cfgWipe.simPublic()
      cfgWipe := False

      def ramWindowWord: Bits = B(0, 26 bits) ## ramWindow.asBits
      def monSenseWord:  Bits = B(0, 25 bits) ## mon.asBits

      // ── Read mux ──────────────────────────────────────────────────────────────────
      // Stage 1's CSR values are added by Tasks 8-10. The default arm is the whole
      // contract for every offset this stage does not implement: spec 3.2 -- "It must
      // reserve them, return zero for absent functions, and never repurpose them."
      when(doRead) {
        // The unconditional zero is the contract for EVERY offset this stage does not
        // implement (spec 3.2: "return zero for absent functions"). The switch below has
        // no `default` arm on purpose -- an offset that is not listed keeps this value,
        // so adding a register can never accidentally un-reserve a neighbour.
        rData := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          is(DebugRegMap.OFF_VERSION) {
            rData := B(DebugRegMap.VERSION_VALUE, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_BUILD_ID) {
            rData := B(buildId, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_FEATURES) {
            // NOT a literal: computed from the STAGE column of debug_regmap.def, so a
            // build cannot advertise a bit whose behaviour it has not built (spec 3.4).
            rData := B(advertisedFeatures, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_CAP_TRACE) {
            val depth = if (historyBuilt) historyDepth else 0
            rData := B(depth, 16 bits) ## B(depth, 16 bits)
          }
          is(DebugRegMap.OFF_CAP_TRACE2) {
            val depth = if (historyBuilt) historyDepth else 0
            rData := B(0, 16 bits) ## B(depth, 16 bits)
          }
          is(DebugRegMap.OFF_CONTROL) {
            rData := controlWord
          }
          is(DebugRegMap.OFF_PC)      { rData := livePc.asBits }
          is(DebugRegMap.OFF_LAST_PC) { rData := lastPc.asBits }
          is(DebugRegMap.OFF_HALT_AFTER_LO) { rData := haltAfterTarget(31 downto 0).asBits }
          is(DebugRegMap.OFF_HALT_AFTER_HI) { rData := haltAfterTarget(63 downto 32).asBits }
          if (stage >= 5) {
            is(DebugRegMap.OFF_BREAK_PC0) { rData := breakPc(0).asBits }
            is(DebugRegMap.OFF_BREAK_PC1) { rData := breakPc(1).asBits }
            is(DebugRegMap.OFF_BREAK_PC2) { rData := breakPc(2).asBits }
            is(DebugRegMap.OFF_BREAK_PC3) { rData := breakPc(3).asBits }
            is(DebugRegMap.OFF_BREAK_PC_CTRL) { rData := B(0, 28 bits) ## breakPcEnable }
            is(DebugRegMap.OFF_BP_SKIP_ONCE) { rData := B(0, 28 bits) ## breakSkipOnce }
            for (i <- 0 until 8) {
              is(DebugRegMap.OFF_HALT_EXC_MASK0 + i * 4) { rData := haltExceptionMask(i) }
            }
            is(DebugRegMap.OFF_A7ODD_CTL) { rData := a7OddCtl }
            is(DebugRegMap.OFF_PCRANGE_CTL) { rData := pcRangeCtl }
            is(DebugRegMap.OFF_PCRANGE_LO)  { rData := pcRangeLo }
            is(DebugRegMap.OFF_PCRANGE_HI)  { rData := pcRangeHi }
            is(DebugRegMap.OFF_PCRANGE_PC0) {
              rData := dbgCommit.map(_.pcRangePc0.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_PC1) {
              rData := dbgCommit.map(_.pcRangePc1.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_PC2) {
              rData := dbgCommit.map(_.pcRangePc2.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_COUNT) {
              rData := dbgCommit.map(s => B(0, 16 bits) ## s.pcRangeCount.asBits)
                .getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC0) {
              rData := dbgCommit.map(_.a7OddPc0.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC1) {
              rData := dbgCommit.map(_.a7OddPc1.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC2) {
              rData := dbgCommit.map(_.a7OddPc2.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_VALUE) {
              rData := dbgCommit.map(_.a7OddValue.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_COUNT) {
              rData := dbgCommit.map(s => B(0, 16 bits) ## s.a7OddEpisodes.asBits)
                .getOrElse(B(0, 32 bits)) }
          }
          is(DebugRegMap.OFF_HALT_CTL) {
            rData := B(0, 31 bits) ## haltAfterArmed
          }
          is(DebugRegMap.OFF_HALT_REASON) {
            rData := B(0, 29 bits) ##
              dbgCommit.map(_.haltReasonDebug).getOrElse(U(0, 3 bits)).asBits
          }
          is(DebugRegMap.OFF_HALT_HIT_PC) {
            rData := dbgCommit.map(_.haltHitPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_VEC) {
            rData := dbgCommit.map(s => s.haltExceptionVector.resize(32).asBits)
              .getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_PC) {
            rData := dbgCommit.map(_.haltExceptionPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_FAULT_ADDR) {
            rData := dbgCommit.map(_.haltExceptionFaultAddress.asBits).getOrElse(B(0, 32 bits))
          }
          // 2026-09-09: these two have been RESERVED in the regmap since Stage 1 with
          // nothing driving them, which is why the REPL always printed `dbl_fault=0` even
          // on a core that had double-faulted. Now backed by `ExceptionUnit.dblFaultPc` /
          // `dblFaultVec` -- the PC of the instruction whose exception processing hit a
          // bus error on its handler-vector read, and the vector it was fetching (the
          // faulting table address is VBR + vec*4). Both stay 0 until a double fault.
          is(DebugRegMap.OFF_DBL_FAULT_PC) {
            rData := dbgCommit.map(_.dblFaultPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_DBL_FAULT_VEC) {
            rData := dbgCommit.map(s => s.dblFaultVec.resize(32).asBits).getOrElse(B(0, 32 bits))
          }
          // 2026-09-09: the fatal-halt ATTRIBUTION. `OFF_HALT_REASON` reports the debug
          // domain's coarse code and collapses every fatal cause to FATAL(4); this is
          // `RobPlugin.haltReason`, the sticky first-wins `socket.HaltReason` value that
          // says WHICH producer halted the core. Without it a fatal halt on hardware is
          // unattributable without an ENABLE_ILA bitstream.
          is(DebugRegMap.OFF_HALT_KIND) {
            rData := dbgCommit.map(s => s.haltKind.resize(32).asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_INST_LO) { rData := macroCount(31 downto 0).asBits }
          is(DebugRegMap.OFF_INST_HI) { rData := macroCount(63 downto 32).asBits }
          // Live, free-running, never halt-captured -- see excCountReg above.
          is(DebugRegMap.OFF_EXC_COUNT) { rData := excCount }
          // ── p141 walker-stall state (live; no halt required) ──────────────────
          // Read these ALONGSIDE OFF_INST_LO/HI: the wedge is identified by the
          // retire count being frozen, and these four words say what it is frozen
          // ON. See DebugRegMap's OFF_STALL_* comment for why they live here in
          // the counter block rather than the halt-captured arch block.
          is(DebugRegMap.OFF_STALL_ARB) {
            rData := stallArb.map(_.logic.dbgArbPack).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_STALL_DC) {
            rData := stallDcache.map(_.logic.dbgStallDcPack).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_STALL_GRANT) {
            rData := stallLsEu.map(_.logic.dbgStallGrantPack).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_STALL_EXC) {
            rData := stallRob.map(_.logic.exc.dbgStallExcPack).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_STALL_WALK) {
            // Both walkers in one word: DTLB in [15:0] (the D-side walker arm C
            // routed through L1D, i.e. the suspect) and ITLB in [15:8] (the
            // control -- if the I-side walker is idle while the D-side is stuck,
            // that localises the stall to the D-cache port hand-over).
            val dtlbBits = stallDtlb.map(_.logic.walker.io.dbgPack).getOrElse(B(0, 16 bits))
            val itlbBits = stallItlb.map(_.logic.walker.io.dbgPack).getOrElse(B(0, 16 bits))
            rData := itlbBits ## dtlbBits
          }
          is(DebugRegMap.OFF_HALT_HIT_INST_LO) {
            rData := haltHitInstCount(31 downto 0).asBits
          }
          is(DebugRegMap.OFF_HALT_HIT_INST_HI) {
            rData := haltHitInstCount(63 downto 32).asBits
          }
          is(DebugRegMap.OFF_STATUS) {
            rData := B(0, 27 bits) ##
                     automaticHalt ##    // bit 4  auto-halt latched
                     !effectiveHalt ##   // bit 3  running (inverse of halted)
                     initDoneLatched ##  // bit 2  init-done seen
                     dbgCommit.map(_.exceptionPending).getOrElse(False) ## // bit 1
                     effectiveHalt       // bit 0  effective coherent halt
          }
          is(DebugRegMap.OFF_RAM_WINDOW_LG2) { rData := ramWindowWord }
          is(DebugRegMap.OFF_MON_SENSE)      { rData := monSenseWord }
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            // Deployed layout (debug_ctrl.v:1411): {cpu_reset_count_r, 16'd0}.
            rData := cpuResetCount.asBits ## B(0, 16 bits)
          }
          is(DebugRegMap.OFF_LIVE_VBR)   { rData := dbgSystem.map(_.vbr.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SR)    { rData := dbgSystem.map(s => s.sr.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_A7)    { rData := liveIntWord }
          is(DebugRegMap.OFF_LIVE_USP)   { rData := dbgSystem.map(_.usp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_TC)   { rData := dbgSystem.map(_.tc.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_DTT0) { rData := dbgSystem.map(_.dtt0.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_DTT1) { rData := dbgSystem.map(_.dtt1.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_ITT0) { rData := dbgSystem.map(_.itt0.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_ITT1) { rData := dbgSystem.map(_.itt1.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_SRP)  { rData := dbgSystem.map(_.srp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_URP)  { rData := dbgSystem.map(_.urp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SSP)   { rData := dbgSystem.map(_.msp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_ISP)   { rData := dbgSystem.map(_.isp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_CACR)  { rData := dbgSystem.map(_.cacr.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SFC)   { rData := dbgSystem.map(s => s.sfc.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_DFC)   { rData := dbgSystem.map(s => s.dfc.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_PC)    { rData := livePc.asBits }
          is(DebugRegMap.OFF_LIVE_MMUSR) { rData := dbgSystem.map(_.mmusr.asBits).getOrElse(B(0, 32 bits)) }
          for (i <- 0 until 8) {
            is(DebugRegMap.OFF_LIVE_DREG0 + i * 4) { rData := liveIntWord }
            is(DebugRegMap.OFF_LIVE_AREG0 + i * 4) { rData := liveIntWord }
          }
          for (i <- 0 until 8) {
            is(DebugRegMap.OFF_ARCH_D0 + i * 4) { rData := archWord(i) }
            is(DebugRegMap.OFF_ARCH_A0 + i * 4) { rData := archWord(8 + i) }
          }
          is(DebugRegMap.OFF_ARCH_USP)  { rData := archWord(16) }
          is(DebugRegMap.OFF_ARCH_SSP)  { rData := archWord(17) }
          is(DebugRegMap.OFF_ARCH_ISP)  { rData := archWord(18) }
          is(DebugRegMap.OFF_ARCH_SR)   { rData := archWord(19) }
          is(DebugRegMap.OFF_ARCH_VBR)  { rData := archWord(20) }
          is(DebugRegMap.OFF_ARCH_CACR) { rData := archWord(21) }
          is(DebugRegMap.OFF_ARCH_TC)   { rData := archWord(22) }
          is(DebugRegMap.OFF_ARCH_ITT0) { rData := archWord(23) }
          is(DebugRegMap.OFF_ARCH_ITT1) { rData := archWord(24) }
          is(DebugRegMap.OFF_ARCH_DTT0) { rData := archWord(25) }
          is(DebugRegMap.OFF_ARCH_DTT1) { rData := archWord(26) }
          is(DebugRegMap.OFF_ARCH_URP)  { rData := archWord(27) }
          is(DebugRegMap.OFF_ARCH_SRP)  { rData := archWord(28) }
          is(DebugRegMap.OFF_ARCH_PC)   { rData := archWord(29) }
          is(DebugRegMap.OFF_ARCH_SFC)  { rData := archWord(30) }
          is(DebugRegMap.OFF_ARCH_DFC)  { rData := archWord(31) }
          is(DebugRegMap.OFF_ARCH_APPLY) {
            rData := B(0, 31 bits) ## archApplyBusy
          }
          is(DebugRegMap.OFF_ARCH_STATUS) {
            rData := B(0, 29 bits) ## archApplyRejected ## archApplyDone ## archApplyBusy
          }
          is(DebugRegMap.OFF_DCACHE_OP) {
            rData := B(0, 26 bits) ## cacheOpError ## cacheOpRejected ##
              cacheOpSel.asBits ## cacheOpDone ## cacheOpBusy
          }
          is(DebugRegMap.OFF_ICACHE_OP) {
            rData := B(0, 26 bits) ## cacheOpError ## cacheOpRejected ##
              cacheOpSel.asBits ## cacheOpDone ## cacheOpBusy
          }
          is(DebugRegMap.OFF_PC_TRACE_HEAD) {
            rData := pcTraceHead.resize(32).asBits
          }
          is(DebugRegMap.OFF_EXC_RING_HEAD) {
            rData := excRingHead.resize(32).asBits
          }
          is(DebugRegMap.OFF_BRANCH_RING_HEAD) {
            rData := branchRingHead.resize(32).asBits
          }
        }
      }

      // ── Write decode ──────────────────────────────────────────────────────────────
      // Writes to anything this stage does not implement are dropped with an OKAY
      // response, which the FSM above already produces unconditionally.
      when(doWrite) {
        switch(awAddr) {
          is(DebugRegMap.OFF_CONTROL) {
            // Read-modify-write through the byte-strobe merge against the SAME word the
            // host reads back, so an unstrobed byte provably cannot change a bit. Bit 1
            // is the Stage-2 step pulse; legacy observation bit 7 remains RAZ/WI. Bit 0
            // is real only in a stage>=2 build.
            val m = merged(controlWord)
            ctrlInitDoneOvr := m(3)
            ctrlColdHold    := m(4)
            // Bit 2 is the deprecated reset-pulse ALIAS of bit 5 (spec 3.3).
            coldPulse       := m(5) || m(2)
            if (stage >= 2) {
              when(wStrb(0)) {
                manualHaltLevel := m(0)
                when(m(0)) { debugStopRequest := True }
                  .otherwise { debugResumeRequest := True }
                when(m(1)) { debugStepRequest := True }
              }
            }
          }
          is(DebugRegMap.OFF_HALT_AFTER_LO) {
            if (stage >= 2) when(wStrb.orR) {
              haltAfterTarget(31 downto 0) :=
                merged(haltAfterTarget(31 downto 0).asBits).asUInt
              haltAfterEpoch := haltAfterEpoch + 1
              haltAfterArmed := False
            }
          }
          is(DebugRegMap.OFF_HALT_AFTER_HI) {
            if (stage >= 2) when(wStrb.orR) {
              haltAfterTarget(63 downto 32) :=
                merged(haltAfterTarget(63 downto 32).asBits).asUInt
              haltAfterEpoch := haltAfterEpoch + 1
              haltAfterArmed := False
            }
          }
          if (stage >= 5) {
            is(DebugRegMap.OFF_BREAK_PC0) {
              when(wStrb.orR) { breakPc(0) := merged(breakPc(0).asBits).asUInt }
            }
            is(DebugRegMap.OFF_BREAK_PC1) {
              when(wStrb.orR) { breakPc(1) := merged(breakPc(1).asBits).asUInt }
            }
            is(DebugRegMap.OFF_BREAK_PC2) {
              when(wStrb.orR) { breakPc(2) := merged(breakPc(2).asBits).asUInt }
            }
            is(DebugRegMap.OFF_BREAK_PC3) {
              when(wStrb.orR) { breakPc(3) := merged(breakPc(3).asBits).asUInt }
            }
            is(DebugRegMap.OFF_BREAK_PC_CTRL) {
              when(wStrb(0)) { breakPcEnable := wData(3 downto 0) }
            }
            is(DebugRegMap.OFF_BP_SKIP_ONCE) {
              when(wStrb(0)) { breakSkipOnce := wData(3 downto 0) }
            }
            for (i <- 0 until 8) {
              is(DebugRegMap.OFF_HALT_EXC_MASK0 + i * 4) {
                when(wStrb.orR) {
                  haltExceptionMask(i) := merged(haltExceptionMask(i)).asBits
                }
              }
            }
            is(DebugRegMap.OFF_A7ODD_CTL) {
              when(wStrb.orR) { a7OddCtl := merged(a7OddCtl).asBits }
            }
            is(DebugRegMap.OFF_PCRANGE_CTL) {
              when(wStrb.orR) { pcRangeCtl := merged(pcRangeCtl).asBits }
            }
            is(DebugRegMap.OFF_PCRANGE_LO) {
              when(wStrb.orR) { pcRangeLo := merged(pcRangeLo).asBits }
            }
            is(DebugRegMap.OFF_PCRANGE_HI) {
              when(wStrb.orR) { pcRangeHi := merged(pcRangeHi).asBits }
            }
          }
          is(DebugRegMap.OFF_HALT_CTL) {
            if (stage >= 2) when(wStrb(0)) {
              when(wData(0)) { haltAfterArmed := True }
              when(wData(2)) { debugClearStickyRequest := True }
            }
          }
          is(DebugRegMap.OFF_RAM_WINDOW_LG2) {
            val req = merged(ramWindowWord)(5 downto 0).asUInt
            ramWindow := Mux(req < U(DebugRegMap.RAM_WINDOW_LG2_MIN, 6 bits),
                             U(DebugRegMap.RAM_WINDOW_LG2_MIN, 6 bits),
                         Mux(req > U(DebugRegMap.RAM_WINDOW_LG2_MAX, 6 bits),
                             U(DebugRegMap.RAM_WINDOW_LG2_MAX, 6 bits),
                             req))
          }
          is(DebugRegMap.OFF_MON_SENSE) {
            // No clamping: every one of the 128 encodings is meaningful to the SoC's
            // video.v sense_response(). Bit 6 is the extended-monitor flag.
            mon := merged(monSenseWord)(6 downto 0).asUInt
          }
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            when(wStrb(0)) {
              cfgWipe := wData(0)
              when(wData(1)) { cpuResetCount := U(0, 16 bits) }
            }
          }
          for (i <- 0 until 8) {
            is(DebugRegMap.OFF_ARCH_D0 + i * 4) { writeArch(i) }
            is(DebugRegMap.OFF_ARCH_A0 + i * 4) { writeArch(8 + i) }
          }
          is(DebugRegMap.OFF_ARCH_USP)  { writeArch(16) }
          is(DebugRegMap.OFF_ARCH_SSP)  { writeArch(17) }
          is(DebugRegMap.OFF_ARCH_ISP)  { writeArch(18) }
          is(DebugRegMap.OFF_ARCH_SR)   { writeArch(19) }
          is(DebugRegMap.OFF_ARCH_VBR)  { writeArch(20) }
          is(DebugRegMap.OFF_ARCH_CACR) { writeArch(21) }
          is(DebugRegMap.OFF_ARCH_TC)   { writeArch(22) }
          is(DebugRegMap.OFF_ARCH_ITT0) { writeArch(23) }
          is(DebugRegMap.OFF_ARCH_ITT1) { writeArch(24) }
          is(DebugRegMap.OFF_ARCH_DTT0) { writeArch(25) }
          is(DebugRegMap.OFF_ARCH_DTT1) { writeArch(26) }
          is(DebugRegMap.OFF_ARCH_URP)  { writeArch(27) }
          is(DebugRegMap.OFF_ARCH_SRP)  { writeArch(28) }
          is(DebugRegMap.OFF_ARCH_PC)   { writeArch(29) }
          is(DebugRegMap.OFF_ARCH_SFC)  { writeArch(30) }
          is(DebugRegMap.OFF_ARCH_DFC)  { writeArch(31) }
        }
      }

      // Clear only the snapshotted dirty set. Writes staged after START, including a
      // write on this exact completion cycle, survive as the next transaction's work.
      if (stage >= 3) when(archApplyComplete) {
        archDirty := (archDirty & ~archApplyDirty) | archApplyRewrite | archWriteMask
      }

      // An automatic stop is one-shot. Clear the arm after the ROB acknowledges the
      // hit; resume cannot immediately retrigger against the same absolute target.
      if (stage >= 2) when(haltAfterConsumed) { haltAfterArmed := False }

      // ── SoC-fabric configuration wipe (spec 15.3) ────────────────────────────────
      // Applied after the write decode on purpose: in SpinalHDL a later `when` wins, which
      // is how debug_ctrl.v's cfg_wipe arm "overrides an in-flight config write without
      // needing its own priority encoder". The wipe list is exactly the deployed one
      // (debug_ctrl.v:3024-3062) restricted to the registers Stage 1 owns -- it does NOT
      // clear cold-reset hold or the init-done override, because the deployed wipe does
      // not either and feature bit 2 must mean the same thing on both cores (spec 15.3).
      when(cfgWipe) {
        ramWindow := U(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits)
        mon       := U(DebugRegMap.MON_SENSE_POR, 7 bits)
        if (stage >= 3) {
          for (i <- 0 until 32) archShadow(i) := B(0, 32 bits)
          archDirty := B(0, 32 bits)
          archApplyRewrite := B(0, 32 bits)
        }
        if (stage >= 4) {
          cacheOpBusy := False
          cacheOpDone := False
          cacheOpRejected := False
          cacheOpError := False
          cacheOpLaunched := False
          cacheOpSel := 0
        }
        if (stage >= 5) {
          for (i <- 0 until 4) breakPc(i) := 0
          breakPcEnable := 0
          breakSkipOnce := 0
          for (i <- 0 until 8) haltExceptionMask(i) := 0
        }
      }

      // ── The spec-15.1 wipe, and the sticky latch it overrides ────────────────────
      // POSITION IS LOAD-BEARING, and this is the one subtle thing in this file.
      // SpinalHDL resolves several assignments to the same register by LAST ASSIGNMENT
      // WINS in elaboration order (the generated process emits them in order, so a later
      // `:=` overwrites an earlier one in the same cycle). This block is elaborated AFTER
      // the CONTROL write arm above, so in the cycle where a CONTROL write lands on the
      // very same edge that asserts the CPU reset, `ctrlInitDoneOvr := m(3)` is issued
      // first and `ctrlInitDoneOvr := False` second -- the wipe wins, which is the
      // required precedence: a host write racing the reset must not survive the reset it
      // raced. Expressing it the other way round (wipe first, write second) would let a
      // host resurrect the override in the same cycle the CPU restarted, i.e. exactly the
      // stale claim spec 15.1 forbids. `when`/`elsewhen` gives the same precedence WITHIN
      // this block: the reset edge beats a still-high `initDoneSeen` level for the wipe
      // cycle, and if that level is genuinely still high the cycle after, the sticky latch
      // simply re-arms from live SoC truth, which is correct and intended.
      // The saturating reset counter's INCREMENT is here for the same reason, and its
      // collision is the mirror image: a host bit-1 clear (`cpuResetCount := 0`, in the
      // write decode above) landing on the very same cycle as a genuine reset edge must
      // NOT swallow that edge, or the host is told "no reset occurred" about a reset that
      // did. Being elaborated after the write decode, the increment wins -- matching the
      // deployed reference, whose clear is at debug_ctrl.v:2417 and whose increment is at
      // :2709, later in the same always block. (Review finding, Task 11 fix pass.)
      // MAINTENANCE RULE: any future statement assigning `ctrlInitDoneOvr`,
      // `initDoneSticky` or `cpuResetCount` must be placed ABOVE this block, never below
      // it. Task 10's and Task 11's write-decode additions all go inside the
      // `when(doWrite)` switch above, so they are; Task 10's `cfgWipe` block is a PEER of
      // this one, placed after the write decode for exactly the same last-wins reason, and
      // assigns a disjoint set of registers (`ramWindow`, `mon`), so the order between
      // those two blocks is free.
      when(cpuRstEvent) {
        ctrlInitDoneOvr := False
        if (stage >= 4) {
          cacheOpBusy := False
          cacheOpDone := False
          cacheOpRejected := True
          cacheOpError := True
          cacheOpLaunched := False
        }
        initDoneSticky  := False
        when(cpuResetCount =/= U(0xFFFF, 16 bits)) {
          cpuResetCount := cpuResetCount + 1
        }
      } elsewhen (initDoneSeen) {
        // A level input, latched sticky so a debugger attaching after DDR calibration
        // still observes it -- within this CPU's lifetime, not a previous one.
        initDoneSticky := True
      }

      GenerationFlags.simulation {
        // "CPU reset cannot change surviving debug configuration" (spec section 13).
        // The only legal movers of this vector are an applied AXI write and a cfg wipe.
        // `ctrlInitDoneOvr` is deliberately ABSENT: spec 15.1 classifies it as CPU-coupled
        // runtime state that Task 9 WIPES on the CPU-reset edge, so it is not surviving
        // configuration and including it here would make this assertion fire on the first
        // CPU reset. `initDoneSticky` is absent for the same reason. What remains is
        // exactly debug_ctrl.v:2447-2462's "host configuration" set for Stage 1. This
        // lives inside `dbgCd on {...}` (unlike the porChecks assertion above) because the
        // condition it checks -- `cpuRstLevel`, the socket reset -- is a different signal
        // from this domain's own `dbgRst`, so the dead-code tautology that forced the
        // first assertion out of this domain does not apply here.
        val cfgVec = ctrlColdHold ## ramWindow.asBits ## mon.asBits
        val cfgVecPrev = RegNext(cfgVec) init (
          False ##
          B(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits) ##
          B(DebugRegMap.MON_SENSE_POR, 7 bits))

        /** THE ONE-CYCLE SKEW, and why this register exists (review finding, Task 11 fix
          * pass). `doWrite` and `cfgWipe` are write-ENABLE signals: they are true in the
          * cycle a write is APPLIED, whereas `cfgVec` only takes the new value on the NEXT
          * edge. Comparing the two at the same instant -- `!doWrite && !cfgWipe &&
          * (cfgVec =/= cfgVecPrev)` -- is therefore always off by one: in the cycle the
          * change becomes visible, the mover that caused it has already gone low, so the
          * check fires on EVERY legitimate configuration write. That is not a corner case:
          * spec 10.1 exists precisely so the host can program `cold_reset_hold`, the RAM
          * window and the monitor sense WHILE CPU reset is held (the real Q700 boot
          * window), which is exactly when `cpuRstLevel` is high. Aligning the movers to
          * the change they cause -- one cycle of delay, same edge -- restores the intended
          * meaning: a change with NO mover in the cycle that produced it. */
        val cfgMoved = RegNext(doWrite || cfgWipe) init False
        assert(!(cpuRstLevel && !cfgMoved && (cfgVec =/= cfgVecPrev)),
          "DebugCtrlPlugin: surviving debug configuration changed while CPU reset was " +
          "asserted (spec section 13)",
          FAILURE)
        if (stage >= 5) {
          val stage5Cfg = haltExceptionMask.reverse.reduce(_ ## _) ##
            breakPc.reverse.map(_.asBits).reduce(_ ## _) ## breakPcEnable ## breakSkipOnce
          val stage5CfgPrev = RegNext(stage5Cfg) init B(0, widthOf(stage5Cfg) bits)
          val stage5RuntimeMove = dbgCommit.map(_.breakpointHit.valid).getOrElse(False) ||
            frontendDebug.map(_.skipConsumed.orR).getOrElse(False)
          val stage5CfgMoved = RegNext(doWrite || cfgWipe || stage5RuntimeMove) init False
          assert(!(cpuRstLevel && !stage5CfgMoved && (stage5Cfg =/= stage5CfgPrev)),
            "DebugCtrlPlugin: Stage-5 breakpoint/exception configuration changed while " +
            "CPU reset was asserted", FAILURE)
        }
      }

      coldResetPulse := coldPulse
      coldResetHold  := ctrlColdHold
      ramWindowLg2   := ramWindow
      monSense       := mon
    }

    // A stably-typed handle that is either a real, fully-elaborated `CsrArea`
    // (enable=true) or `null` (enable=false, nothing built -- none of `CsrArea`'s
    // `Reg()` calls ever run). MUST stay `dbgCd on new CsrArea` as a single expression
    // assigned directly to this `val` (matching the exact call-site shape `porChecks`/
    // `por` below use): review finding -- an earlier version of this line instead
    // declared `CsrArea` as its own separately-defined named class and populated a
    // `var csr` from inside a nested `if(enable) { csr = ... }`, which silently defeated
    // SpinalHDL's val-name-reflection naming pass (every `CsrArea` signal fell back to
    // auto-generated `_zz_NNN` names instead of `DebugCtrlPlugin_logic_csr_*`) -- caught
    // by this task's own required netlist byte-diff, not by any test.
    val csr: CsrArea = if (enable) (dbgCd on new CsrArea) else null

    // The service is the entire cross-plugin seam. In particular, do not expose
    // sibling wires from this plugin or reach into RobPlugin.logic from a wiring area.
    if (enable && stage >= 2) {
      // The CPU domain can leave reset before this reset-less debug POR generator has
      // completed. Never let a transient/reset value acquire halt ownership; this is
      // the command-side counterpart of AXI READY being held low while dbgRst is active.
      dbgCommit.foreach(_.request(csr.debugStopRequest && !dbgRst,
        csr.debugResumeRequest && !dbgRst && !csr.archApplyBusy && !csr.cacheOpBusy,
        csr.debugStepRequest && !dbgRst && !csr.archApplyBusy && !csr.cacheOpBusy,
        csr.debugClearStickyRequest && !dbgRst))
      dbgCommit.foreach(_.configureHaltAfter(csr.haltAfterTarget, csr.haltAfterEpoch,
        csr.haltAfterArmed && !dbgRst, csr.haltAfterInvalidate && !dbgRst))
      if (stage >= 5) {
        val exceptionMask = csr.haltExceptionMask.reverse.reduce(_ ## _)
        dbgCommit.foreach(_.configureExceptionMask(exceptionMask))
        dbgCommit.foreach(_.configureA7OddHalt(csr.a7OddCtl(0) && !dbgRst,
          csr.a7OddCtl(31 downto 16).asUInt))
        dbgCommit.foreach(_.configurePcRangeHalt(csr.pcRangeCtl(0) && !dbgRst,
          csr.pcRangeLo.asUInt, csr.pcRangeHi.asUInt))
        frontendDebug.foreach(_.configure(csr.breakPc, csr.breakPcEnable,
          csr.breakSkipOnce))
      } else {
        dbgCommit.foreach(_.configureExceptionMask(B(0, 256 bits)))
        dbgCommit.foreach(_.configureA7OddHalt(False, U(0, 16 bits)))
        dbgCommit.foreach(_.configurePcRangeHalt(False, U(0, 32 bits), U(0, 32 bits)))
      }
    }
    if (enable && stage >= 3) {
      dbgSystem.foreach(_.requestApply(csr.systemApply))
      debugMemory.foreach { service =>
        val cmd = spinal.lib.Flow(DebugMemoryCommand())
        cmd.valid := csr.debugMaintCmd.valid && !dbgRst
        cmd.payload := csr.debugMaintCmd.payload
        service.request(cmd)
      }
    }

    if (!enable) {
      // ── enable=false: every port declared above is tied to a dead-idle value, zero
      // debug logic (`CsrArea` -- the AXI FSM and every CSR register, ~20 registers) is
      // instantiated (spec `2026-08-18-vio-jtag-debug-design.md` V4, mirrored from
      // `VioProbePlugin`'s own `enable` pattern). READY held low forever is a legal AXI4
      // idle state (the master simply never completes a transaction against this slave),
      // not a protocol violation -- exactly "this plugin does not exist".
      dbgAxi.awready := False
      dbgAxi.wready  := False
      dbgAxi.arready := False
      dbgAxi.bvalid  := False
      dbgAxi.bresp   := DbgAxiLite.RESP_OKAY
      dbgAxi.rvalid  := False
      dbgAxi.rdata   := B(0, DebugRegMap.DBG_DW bits)
      dbgAxi.rresp   := DbgAxiLite.RESP_OKAY
      coldResetPulse := False
      coldResetHold  := False
      ramWindowLg2   := U(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits)
      monSense       := U(DebugRegMap.MON_SENSE_POR, 7 bits)
    }

    // ── Required assertion (spec section 13) ────────────────────────────────────────
    // Deliberately placed in the `porCd` (BOOT-kind, reset-LESS) domain rather than inside
    // the `csr` Area above: SpinalHDL elaborates a synchronous-reset domain's clocked
    // process as `if(reset) {...resets...} else {...this code...}`, so a check written
    // inside `dbgCd on {...}` only ever runs in the `else` branch -- i.e. only when
    // `dbgRst == 0` -- making its own `dbgRst && (...)` term a tautological False and the
    // assert a dead check that can never fire (review finding, Task 7 fix pass). `porCd`
    // has no reset at all, so its process has no if/else gating and this runs
    // unconditionally every cycle, exactly like the BOOT-domain POR counter above.
    // With enable=false the condition is trivially False (READY is tied False above), so
    // this stays a harmless, never-firing check rather than a dead one.
    val porChecks = porCd on new Area {
      GenerationFlags.simulation {
        assert(!(dbgRst && (dbgAxi.awready || dbgAxi.wready || dbgAxi.arready)),
          "DebugCtrlPlugin: AXI READY asserted while the debug domain is in reset " +
          "(spec section 13: 'debug reset cannot accept AXI requests')",
          FAILURE)
      }
    }
  }
}
