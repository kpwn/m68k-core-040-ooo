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
                      val historyDepth: Int  = 32,
                      val detailedPerf: Boolean = false) extends FiberPlugin
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
        // Share BranchEu's int write port rather than owning one.  The
        // exception A7 write moved to BranchEuPlugin.IntWbKey (FullCoreSynth
        // a7Wr, priority 0), which left "excA7" referenced by this line ALONE
        // -- i.e. a whole distinct physical write port for writes that only
        // happen at an effective halt.  Under the PRF's LVT every write port
        // costs a FULL register-file copy (measured marginal cost of the 6th
        // port: ~900 LUT, plus a 32-bit write-data broadcast and a 6-bit
        // write-address fanout).  A debug write and a BranchEu write cannot be
        // valid together: debug writes only with the pipe drained and retire
        // blocked.  RegFilePlugin asserts in simulation if two writers sharing
        // a key are ever valid in the same cycle, so this is CHECKED, not
        // argued -- the same basis as a7Wr's own comment in FullCoreSynth.
        debugIntWrite = rf.newWrite(latency = 1,
                          sharingKey = m68k040.execute.BranchEuPlugin.IntWbKey,
                          priority = 2)
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
    val fetchFeed = if (historyEnabled && stage >= 5)
      host.get[m68k040.services.DecodeFeedService] else None

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
    // 2026-09-17 multi-hot evidence (DebugRegMap.OFF_MULTIHOT_*). Same `host.get`
    // Option discipline as every reader above: the Stage-1 standalone fixture hosts no
    // I-cache, and a hard reach-in would break its elaboration. An absent peer reads as
    // a clean zero -- which for THIS register is a meaningful answer, not a lie: zero
    // means "no multi-hot was detected", and that is the reading which FALSIFIES the
    // duplicate-entry hypothesis. That is exactly why every source is proven live in
    // simulation rather than assumed (see DebugCtrlMultiHotSpec).
    val mhIcache    = if (enable) host.get[m68k040.cache.IcachePlugin] else None
    val historyBuilt = historyEnabled && debugHistory.nonEmpty
    val unavailableFeatures = Set("dcache_probe") ++
      (if (fetchFeed.nonEmpty) Set.empty[String] else Set("fetch_word_check")) ++
      (if (historyBuilt) Set.empty[String] else Set("pc_trace", "exc_ring", "branch_ring")) ++
      (if (debugMemory.nonEmpty) Set.empty[String] else Set("cache_maint_only")) ++
      (if (frontendDebug.nonEmpty) Set.empty[String] else Set("break_pc_multi")) ++
      (if (dbgCommit.nonEmpty) Set.empty[String] else Set("halt_exc_mask")) ++
      // `perf_counters` means "EVERY advertised performance counter has a real
      // producer" (spec 3.4). The windowed block degrades gracefully -- an absent peer
      // plugin yields a clean zero -- but a clean zero is exactly what a dead probe
      // looks like, so the BIT is withheld unless every producer is present. A build
      // with, say, no I-cache still serves the other eleven counters; it just does not
      // claim the capability.
      (if (stage >= 2 && stallRob.nonEmpty && excCountHistory.nonEmpty &&
           stallDcache.nonEmpty && mhIcache.nonEmpty &&
           stallDtlb.nonEmpty && stallItlb.nonEmpty)
        Set.empty[String] else Set("perf_counters"))
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

      /** FMAX (2026-09-17). A REGISTERED copy of the one AW address decode whose result
        * leaves this plugin combinationally.
        *
        * `haltAfterInvalidate` -- the "the host reprogrammed the halt-after target, drop
        * any in-flight comparison" pulse -- was built as
        * `doWrite && wStrb.orR && (awAddr === OFF_HALT_AFTER_LO || awAddr === OFF_HALT_AFTER_HI)`
        * and handed straight to `DebugCommitService.configureHaltAfter`. That put a
        * 20-bit-wide double comparator on `awAddr` at the HEAD of the longest cone in the
        * design: `RobPlugin.haltAfterInvalidateIn` feeds `haltAfterDue` and
        * `haltAfterRetireBlock`, both of which gate RETIRE, which gates the LS EU
        * completion arbitration, which drives `loadProbeResolvePort.valid` ->
        * `probeResolveMatchesRead` -> `probeReadHitVec` -> `DcachePlugin.probeLineLine`.
        * That is exactly the `csr_awAddr_reg => RobPlugin_logic_exc_activeReg_reg` (the
        * 200 MHz design WNS) and `csr_awAddr_reg => DcachePlugin_logic_probeLineLine_reg`
        * (56 endpoints) family pair.
        *
        * The match is computed from `dbgAxi.awaddr` on the SAME edge that captures
        * `awAddr`, so it is available whenever `awAddr` is and this is an EXACT
        * substitution, not an approximation -- no cycle of behaviour moves. */
      val awIsHaltAfter = RegInit(False); awIsHaltAfter.simPublic()

      /** FMAX companion to `awIsHaltAfter`: `wStrb.orR` precomputed on the edge that
        * captures `wStrb`. Together the two turn `haltAfterInvalidate` into a single
        * LUT6 over five flops (`awPend`, `wPend`, `bPend`, `awIsHaltAfter`, `wStrbAny`)
        * where it used to be an OR of two 20-bit comparators, an OR-reduce of the four
        * strobe bits, and the `doWrite` conjunction. EXACT substitution: both registers
        * are written on exactly the edges that write the fields they summarise. */
      val wStrbAny = RegInit(False); wStrbAny.simPublic()

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

      // ── Free-running core_clk cycle counter (OFF_CYCLE_LO / OFF_CYCLE_HI) ────
      // The ONLY measurement of the core clock this project has. Everything else
      // is three copies of the same INTENT agreeing with each other -- the SoC's
      // `buildinfo core_clk_hz`, `fpga_top_clocks.vh`'s MMCM divides, and the XDC
      // `core_mmcm_clkout0` period. Agreement between three statements of what we
      // ASKED for is not evidence of what the silicon DOES. Read OFF_CYCLE_LO
      // twice a known wall-clock interval apart and divide: that is the core
      // clock, measured.
      //
      // No producer, no service lookup, no `stage` gate and no Option: this
      // counter needs nothing but the clock edge, which is exactly the property
      // that makes it a clock probe. `excCountReg` above is Option-gated because
      // it needs a ROB to count anything; this one does not.
      //
      // LIFETIME. `CsrArea` runs in `dbgCd` (line ~151), whose reset is the debug
      // POR -- NOT the socket reset this block can itself request. So the counter
      // keeps advancing across a CPU reset, across a debug halt, and across a
      // wedge. That is deliberate: a counter that stops when the CPU stops cannot
      // distinguish "the clock is dead" from "the CPU is dead", and telling those
      // two apart is the whole job at a wedge.
      //
      // 64 bits, sliced LO/HI exactly like OFF_INST_LO/HI, so a read pair that
      // straddles a LO wrap can tear the same way OFF_INST does and the host
      // handles it the same way. LO alone answers the frequency question: it
      // wraps every 21.5 s at 200 MHz.
      //
      // COST: 64 flops plus one carry chain whose only fanout is the read mux --
      // a self-contained loop that cannot lengthen an existing path.
      val cycleCountReg = Reg(UInt(64 bits)) init 0
      cycleCountReg := cycleCountReg + 1
      cycleCountReg.simPublic()
      def cycleCount: UInt = cycleCountReg

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
      // ── Read pipeline stage 2 (FMax, 2026-09-17) ─────────────────────────────────
      // Every read is now decoded in one cycle and ANSWERED in the next. Before this
      // change only history-BODY reads took a second cycle (they must: their source is
      // a synchronous BRAM); the ~100-arm CSR mux ran in a single cycle straight into
      // `rData`. `readStage2` is the generalisation of the old `historyReadPending`:
      // it is high for exactly the cycle in which `rData` is latched, for EVERY read.
      // `readIsHistory` says which source stage 2 must take (it is what the old
      // `historyReadPending` meant, now qualified: `readStage2 && readIsHistory`).
      // See the read mux below.
      val historyReadIssue = Bool()
      val readStage2 = RegInit(False); readStage2.simPublic()
      val readIsHistory = RegInit(False); readIsHistory.simPublic()
      /** Stage-2 source select for the LIVE INTEGER registers (OFF_LIVE_A7,
        * OFF_LIVE_DREG*, OFF_LIVE_AREG*). See `liveIntPhysAddr` below for the path this
        * takes off `arAddr`'s cone. */
      val readIsLiveInt = RegInit(False); readIsLiveInt.simPublic()

      // ── Per-REGION registered partial read words (read mux stage 1) ───────────────
      // One 32-bit register per address region of `DebugRegMap`. Exactly one can be
      // non-zero for any address, so stage 2 ORs them; see the read mux for the full
      // argument and for why the mux was split at all.
      val rdCore  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdCore.simPublic()
      val rdLane  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdLane.simPublic()
      val rdCount = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdCount.simPublic()
      val rdArch  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdArch.simPublic()
      val rdLive  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdLive.simPublic()
      val rdHead  = Reg(Bits(DebugRegMap.DBG_DW bits)) init 0; rdHead.simPublic()

      // ── Registered copies of the four p141 stall packs (FMax, 2026-09-17) ─────────
      // `DcachePlugin.dbgStallDcPack`, `LsEuPlugin.dbgStallGrantPack`,
      // `RobPlugin.exc.dbgStallExcPack` and the two walker `dbgPack`s are COMBINATIONAL
      // bundles of live, late core state -- `dcache.loadCmd.ready`, `ldGrantOk`,
      // `dcIdleForMaint` and friends, i.e. arbitration outputs, some of the latest
      // signals in the machine. Feeding them straight into the CSR read mux put the
      // whole mux behind them. They are observability only and a wedge does not move,
      // so one cycle of staleness is meaningless; what it buys is that the mux now
      // starts from a flop.
      //
      // The registers exist only where the peer plugin does, so the standalone Stage-1
      // fixtures (no D-cache, no LS EU, no ROB, no TLBs) build unchanged.
      // MERGE NOTE (2026-09-17): `OFF_STALL_ARB` (the AxiDMerge post-mortem, master
      // commit e5bace11) landed on master while this branch was restructuring the CSR
      // read path into registered per-region muxes. `dbgArbPack` is the same kind of
      // signal as the four below -- a deep combinational pack of live arbitration state
      // -- so it is registered on the same terms rather than wired into the mux raw.
      val stallArbPackReg = stallArb
        .map(a => RegNext(a.logic.dbgArbPack) init B(0, 32 bits))
        .getOrElse(B(0, 32 bits))
      val stallDcPackReg = stallDcache
        .map(d => RegNext(d.logic.dbgStallDcPack) init B(0, 32 bits))
        .getOrElse(B(0, 32 bits))
      val stallGrantPackReg = stallLsEu
        .map(l => RegNext(l.logic.dbgStallGrantPack) init B(0, 32 bits))
        .getOrElse(B(0, 32 bits))
      val stallExcPackReg = stallRob
        .map(r => RegNext(r.logic.exc.dbgStallExcPack) init B(0, 32 bits))
        .getOrElse(B(0, 32 bits))
      // Both walkers in one word: DTLB in [15:0] (the D-side walker arm C routed
      // through L1D, i.e. the suspect) and ITLB in [31:16] (the control -- if the
      // I-side walker is idle while the D-side is stuck, that localises the stall to
      // the D-cache port hand-over).
      val stallWalkPackReg = if (stallDtlb.nonEmpty || stallItlb.nonEmpty) {
        val dtlbBits = stallDtlb.map(_.logic.walker.io.dbgPack).getOrElse(B(0, 16 bits))
        val itlbBits = stallItlb.map(_.logic.walker.io.dbgPack).getOrElse(B(0, 16 bits))
        RegNext(itlbBits ## dtlbBits) init B(0, 32 bits)
      } else B(0, 32 bits)
      // ── Multi-hot evidence words (2026-09-17) ────────────────────────────────
      // Registered on the same terms as the stall packs above: these are deep
      // combinational reach-ins into three different plugins (and, for the TLBs,
      // across a sub-Component boundary), and the read mux must not carry them raw.
      //
      // SIX sites, deliberately not merged. An ITLB multi-hot mistranslates a correct
      // PC and explains a wild BRANCH TARGET; a DTLB one explains a wild DATA address;
      // a D-cache STORE one is a write into a way that never matched. Which bit is set
      // is most of the diagnosis, so each keeps its own bit, its own saturating counter
      // and its own first-occurrence address.
      def mhSticky(o: Option[Bool]): Bool = o.getOrElse(False)
      def mhCount(o: Option[UInt]): UInt  = o.getOrElse(U(0, 4 bits))
      def mhAddr(o: Option[UInt]): UInt   = o.getOrElse(U(0, 32 bits))
      val mhIc  = mhIcache.map(_.logic.dbgMultiHot)
      val mhDc  = stallDcache.map(_.logic)
      val mhSt  = Seq(
        mhSticky(mhIc.map(_.sticky)),
        mhSticky(mhDc.map(_.dbgMultiHotLoad.sticky)),
        mhSticky(mhDc.map(_.dbgMultiHotProbe.sticky)),
        mhSticky(mhDc.map(_.dbgMultiHotStore.sticky)),
        mhSticky(stallItlb.map(_.logic.tlb.io.dbgMultiHotSticky)),
        mhSticky(stallDtlb.map(_.logic.tlb.io.dbgMultiHotSticky)))
      val mhCt  = Seq(
        mhCount(mhIc.map(_.count)),
        mhCount(mhDc.map(_.dbgMultiHotLoad.count)),
        mhCount(mhDc.map(_.dbgMultiHotProbe.count)),
        mhCount(mhDc.map(_.dbgMultiHotStore.count)),
        mhCount(stallItlb.map(_.logic.tlb.io.dbgMultiHotCount)),
        mhCount(stallDtlb.map(_.logic.tlb.io.dbgMultiHotCount)))
      val mhStatusRaw = Cat(
        mhCt.reverse.map(_.asBits).reduce(_ ## _),   // [31:8] six 4-bit counters
        B(0, 2 bits),                                 // [7:6]  reserved
        mhSt.reverse.map(_.asBits).reduce(_ ## _))    // [5:0]  six sticky bits
      val multiHotStatusReg = RegNext(mhStatusRaw.resize(32)) init B(0, 32 bits)
      val multiHotAddrRegs = Seq(
        mhAddr(mhIc.map(_.addr)),
        mhAddr(mhDc.map(_.dbgMultiHotLoad.addr)),
        mhAddr(mhDc.map(_.dbgMultiHotProbe.addr)),
        mhAddr(mhDc.map(_.dbgMultiHotStore.addr)),
        mhAddr(stallItlb.map(_.logic.tlb.io.dbgMultiHotVpn)),
        mhAddr(stallDtlb.map(_.logic.tlb.io.dbgMultiHotVpn))
      ).map(a => RegNext(a.asBits) init B(0, 32 bits))
      multiHotStatusReg.simPublic()
      multiHotAddrRegs.foreach(_.simPublic())

      // ══ WINDOWED PERFORMANCE COUNTERS (OFF_PERF_*, 2026-09-17) ════════════════
      // The owner's request, verbatim: "do we have any perf counters available? i
      // would like a way to zero 'em, too, such that i can capture a precise path."
      //
      // Everything above this line in the 0x01000 block is FREE-RUNNING and READ-ONLY.
      // The only way to measure a bounded piece of execution with those is to subtract
      // two reads -- and OFF_CYCLE_LO/HI and OFF_INST_LO/HI can TEAR across a LO wrap,
      // so even the difference is not trustworthy. This block is the answer: a second,
      // independently gated counter set that can be ZEROED and FROZEN, so a read taken
      // while it is frozen describes exactly one window and cannot tear at all.
      //
      // The free-running registers are deliberately NOT made clearable. OFF_CYCLE_* is
      // the only measurement of the core clock this project has and must keep running
      // across a CPU reset, a halt and a wedge; OFF_INST_* is `RobPlugin.macroCount`,
      // which is ALSO the comparand for the absolute halt-after target, so zeroing it
      // would silently re-aim every armed OFF_HALT_AFTER_*.
      //
      // ── TIMING DISCIPLINE (2026-09-17 owner directive) ────────────────────────
      // "is it possible to have the implementation be aware that we are super relaxed
      // with timing and a few cycles latency in updating the values are OK". Taken
      // literally and applied uniformly: EVERY counter is fed from a REGISTERED copy of
      // its event, never from the producer's live combinational cone.
      //
      // THE `RegNext` ON EACH TAP IS LOAD-BEARING. Do not "optimise" it away as a
      // redundant pipeline stage. This design closes 200 MHz at +0.002..+0.010 ns and
      // several of these sources are among the latest signals in the machine
      // (`loadMissDiscovered` is a tag-compare verdict; `s1Unresolved` feeds the I-cache
      // accept gate; `retire0` gates the whole retire cone). A flop between the source
      // and a 32-bit carry chain means the counter's adder starts at a flop output and
      // ends at a flop input -- a self-contained loop that CANNOT lengthen any existing
      // path. Deleting the flop would put a 32-bit incrementer's enable directly on
      // those nets. Each tap costs exactly one extra fanout load on its source; that is
      // the entire price.
      //
      // ── LATENCY, AND WHERE IT LANDS ────────────────────────────────────────────
      // All of the latency is at the window EDGES, none of it in the totals:
      //   * direct-pulse lanes (MISPRED, FLUSH, INST, BRANCH, DC_MISS, STALL_*) are one
      //     core clock behind their event;
      //   * edge-detected lanes (IC_MISS, DTLB_WALK, ITLB_WALK) are two, because the
      //     level they watch is differentiated from two registered copies;
      //   * `perfRunQ` -- the RUN gate -- is delay-matched to the one-cycle lanes, so a
      //     FREEZE does not drop an event that already happened. The window boundary
      //     moves with the pipeline instead of cutting across it. This is the one
      //     correctness risk the latency introduces and it is handled by construction,
      //     not by hoping the window is long.
      // Residual: at most one event per lane at each edge, two on the edge-detected
      // lanes. Over a window of millions of cycles that is noise.
      val perfBuilt = stage >= 2

      /** RUN level. POR = 1 so a build behaves like the free-running counters until a
        * host deliberately takes a window. Written by OFF_PERF_CTL bit 1. */
      val perfRun = if (perfBuilt) RegInit(True) else True

      /** CLEAR request: one cycle, raised by the OFF_PERF_CTL arm of the WRITE decode.
        *
        * IT BELONGS IN `switch(awAddr)` UNDER `when(doWrite)` AND NOWHERE ELSE. The
        * multi-hot clear was first written into a READ-region `switch(arAddr)`, where it
        * elaborated cleanly, emitted no warning, read back perfectly and silently never
        * fired. `PerfCounterSpec` tests the clear rather than assuming it, for exactly
        * that reason. */
      val perfClear = if (perfBuilt) RegInit(False) else False
      if (perfBuilt) {
        perfRun.simPublic(); perfClear.simPublic()
        perfClear := False
      }

      /** The RUN gate, delayed by exactly the one flop every direct tap goes through.
        *
        * Without the match, freezing at cycle W would drop the event that happened at
        * W-1 (its registered copy only arrives at W). With it, the increment at cycle
        * T+1 is gated by RUN as it was at cycle T -- the same cycle the event happened
        * -- so the window is closed on EVENT time, not on observation time. */
      val perfRunQ = if (perfBuilt) (RegNext(perfRun) init True) else True

      /** A producer tap: one flop in the debug domain, or a hard False when the peer
        * plugin is absent from this build. `initValue` exists for levels whose idle
        * state is high (none today; walker IDLE is inverted at the tap instead). */
      def perfTap(src: Option[Bool]): Bool =
        if (!perfBuilt) False else src.map(s => RegNext(s) init False).getOrElse(False)

      /** Rising edge of an already-REGISTERED level, differentiated from a second
        * registered copy. Never touches the live net -- that is the whole point. */
      def perfRise(level: Option[Bool]): Bool = level match {
        case Some(q) => val prev = RegNext(q) init False; q && !prev
        case None    => False
      }

      /** One 32-bit windowed counter.
        *
        * The CLEAR is written LAST so last-assignment-wins gives it the collision: a
        * clear landing on the same cycle as an increment discards the in-flight event
        * rather than carrying it into the new window. That costs at most one event and
        * buys a window that provably starts at zero. */
      def perfCounter(evt: Bool): UInt = {
        val r = Reg(UInt(32 bits)) init 0
        when(evt && perfRunQ) { r := r + 1 }
        when(perfClear) { r := 0 }
        r.simPublic()
        r
      }

      /** 64-bit windowed accumulator; `incr` is 0..2 (the dual retire slots) or a
        * constant 1 (cycles). Same clear-wins ordering as `perfCounter`.
        *
        * A 64-bit carry chain is NOT a timing concern here and does not need splitting:
        * `cycleCountReg` above is already a 64-bit free-running counter in this same
        * area and this design closes 200 MHz with it in place. That is a measurement,
        * not an argument. */
      def perfCounter64(incr: UInt): UInt = {
        val r = Reg(UInt(64 bits)) init 0
        when(perfRunQ) { r := r + incr.resize(64) }
        when(perfClear) { r := 0 }
        r.simPublic()
        r
      }

      // ── Producer taps ─────────────────────────────────────────────────────────
      // `stallRob` / `stallDcache` / `mhIcache` / `stallDtlb` / `stallItlb` are the
      // SAME Option handles the p141 stall packs and the multi-hot evidence already
      // use; an absent peer yields a hard False and the lane reads a clean zero.
      //
      // MISPRED is `branchRedirect` -- `retire0 && p0.retireAlone && mispredictStore(h0)`,
      // the COMMIT-time mispredict redirect. One pulse per mispredicting branch, no
      // wrong-path double counting. Deliberately NOT `debugBranchRetire.mispredicted`,
      // which is gated by `btbUpdateValidComb` and therefore only sees BTB-ELIGIBLE
      // branches -- that would silently UNDERCOUNT and this register's whole history is
      // reading zero and lying.
      //
      // FLUSH is `doFlushReg` -- already a register in the ROB -- i.e. EVERY whole-ROB
      // squash: mispredict, exception/RTE redirect, debug recover, halted PC apply. So
      // FLUSH >= MISPRED by construction and the difference is the non-branch flush
      // traffic. Reading them together is itself a liveness check.
      val perfEvtMispred = perfTap(stallRob.map(_.logic.branchRedirect))
      val perfEvtFlush   = perfTap(stallRob.map(_.logic.doFlushReg))
      val perfLvlRobBusy = perfTap(stallRob.map(_.logic.count =/= 0))
      val perfLvlRetire  = perfTap(stallRob.map(_.logic.retire0))
      val perfEvtBranch  = perfTap(excCountHistory.map(_.branchRetire.valid))
      val perfEvtDcMiss  = perfTap(stallDcache.map(_.logic.loadMissDiscovered))
      // I-cache: `s1Unresolved` is a LEVEL held from miss discovery until the fill is
      // dispatched, so it is edge-detected. It cannot merge two misses: `cmdPort.ready`
      // is gated by `!s1Unresolved`, so no younger command can be accepted behind an
      // unresolved miss and the level always returns low between two of them.
      val perfLvlIcMiss  = if (perfBuilt) mhIcache.map(i => RegNext(i.logic.s1Unresolved) init False) else None
      // Table walkers: `dbgPack(0)` is `fsm.isActive(IDLE)`, so the tap inverts it and
      // its reset value is "idle" -- no spurious start edge out of reset. A walk always
      // returns through IDLE (FINISH -> IDLE -> start), so back-to-back walks cannot
      // merge into one edge either.
      val perfLvlDtlbWalk = if (perfBuilt) stallDtlb.map(t => RegNext(!t.logic.walker.io.dbgPack(0)) init False) else None
      val perfLvlItlbWalk = if (perfBuilt) stallItlb.map(t => RegNext(!t.logic.walker.io.dbgPack(0)) init False) else None
      /** Retired macros this cycle, 0/1/2, from the SAME `macroRetirePc` Flows the PC
        * trace ring records. Cross-checkable against OFF_INST_LO by construction, which
        * is how `PerfCounterSpec` proves this lane is not fabricating numbers. */
      val perfEvtInst = if (perfBuilt) {
        val live = excCountHistory.map(h =>
          h.macroRetirePc(0).valid.asUInt.resize(2) + h.macroRetirePc(1).valid.asUInt.resize(2)
        ).getOrElse(U(0, 2 bits))
        RegNext(live) init 0
      } else U(0, 2 bits)

      // ── The counters ──────────────────────────────────────────────────────────
      // Each is a named `val` so SpinalHDL's val-name reflection gives it a real
      // netlist name (`DebugCtrlPlugin_logic_csr_perfMispred_reg`) instead of a `_zz_`.
      val perfMispred     = if (perfBuilt) perfCounter(perfEvtMispred) else U(0, 32 bits)
      val perfFlush       = if (perfBuilt) perfCounter(perfEvtFlush) else U(0, 32 bits)
      val perfBranch      = if (perfBuilt) perfCounter(perfEvtBranch) else U(0, 32 bits)
      val perfDcMiss      = if (perfBuilt) perfCounter(perfEvtDcMiss) else U(0, 32 bits)
      val perfIcMiss      = if (perfBuilt) perfCounter(perfRise(perfLvlIcMiss)) else U(0, 32 bits)
      val perfDtlbWalk    = if (perfBuilt) perfCounter(perfRise(perfLvlDtlbWalk)) else U(0, 32 bits)
      val perfItlbWalk    = if (perfBuilt) perfCounter(perfRise(perfLvlItlbWalk)) else U(0, 32 bits)
      // The top-line stall: ROB non-empty and nothing retired. Both terms are taps, so
      // the enable is a LUT2 over two flops.
      val perfStallRetire = if (perfBuilt) perfCounter(perfLvlRobBusy && !perfLvlRetire) else U(0, 32 bits)
      // D-cache busy. Reuses `stallDcPackReg(1)`, the ALREADY-registered copy of
      // `DcachePlugin.busy` that OFF_STALL_DC bit 1 reports -- zero extra flops and zero
      // extra fanout on the D-cache. Its reset value is 0 = "not busy", the correct idle
      // polarity, which is why this one does not need a tap of its own.
      val perfStallDc     = if (perfBuilt) perfCounter(stallDcPackReg(1)) else U(0, 32 bits)
      val perfStallWalk   = if (perfBuilt)
        perfCounter(perfLvlDtlbWalk.getOrElse(False) || perfLvlItlbWalk.getOrElse(False))
        else U(0, 32 bits)
      val perfCycle       = if (perfBuilt) perfCounter64(U(1, 1 bits)) else U(0, 64 bits)
      val perfInst        = if (perfBuilt) perfCounter64(perfEvtInst) else U(0, 64 bits)

      // Optional diagnostic producers are accessed only through services. All
      // events use the same one-cycle tap / RUN alignment as the baseline set.
      val detailRob = if (perfBuilt && detailedPerf)
        host.get[m68k040.services.RobPerfDetailService].flatMap(_.robPerfEvents) else None
      val detailDispatch = if (perfBuilt && detailedPerf)
        host.get[m68k040.services.DispatchPerfDetailService].flatMap(_.dispatchPerfEvents) else None
      val detailCounters = (detailRob.toSeq ++ detailDispatch.toSeq).flatMap { events =>
        (0 until events.getWidth).map(bit => perfCounter(perfTap(Some(events(bit)))))
      }
      // Version 1, independent producer counts. Zero means absent, never idle.
      val detailCap = if (!detailedPerf || !perfBuilt) BigInt(0) else
        BigInt(0xD1010000L | (detailRob.map(_.getWidth).getOrElse(0) << 8) |
          detailDispatch.map(_.getWidth).getOrElse(0))

      // ── OFF_PERF_CTL read word ────────────────────────────────────────────────
      // [20:16] is the PRODUCER PRESENCE bitmap and it is the anti-dead-probe device at
      // runtime. A counter with no producer reads zero -- indistinguishable from "the
      // event never happened", which is precisely how a dead probe retires a live
      // hypothesis for free. The host reads these bits FIRST and renders a lane with no
      // producer as absent rather than as zero.
      val perfPresentRob  = perfBuilt && stallRob.nonEmpty && excCountHistory.nonEmpty
      val perfPresentDc   = perfBuilt && stallDcache.nonEmpty
      val perfPresentIc   = perfBuilt && mhIcache.nonEmpty
      val perfPresentDtlb = perfBuilt && stallDtlb.nonEmpty
      val perfPresentItlb = perfBuilt && stallItlb.nonEmpty
      /** LOGICAL counters, not words: cycle and inst are one counter each despite
        * occupying a LO/HI pair. 12 when this block is built, 0 when it is not -- so a
        * host reading 0 here knows the block is absent rather than merely idle. */
      val perfNumCounters = if (perfBuilt) 12 else 0
      val perfCtlWord: Bits =
        B(0, 11 bits) ##                   // [31:21] reserved zero
        Bool(perfPresentItlb) ##           // [20]
        Bool(perfPresentDtlb) ##           // [19]
        Bool(perfPresentIc) ##             // [18]
        Bool(perfPresentDc) ##             // [17]
        Bool(perfPresentRob) ##            // [16]
        B(perfNumCounters, 8 bits) ##      // [15:8] implemented logical counters
        B(0, 6 bits) ##                    // [7:2]  reserved zero
        perfRun ##                         // [1]    RUN level
        False                              // [0]    CLEAR is self-clearing, reads 0

      // ── MMU translation-key probes (OFF_MMU_PROBE_STATUS/_*_COUNT/_CTL) ──────────
      // See `tools/debug/debug_regmap.def` for WHY these exist. Two defects fixed on
      // 2026-09-17 both produce a correct VA translated to the wrong physical page as a
      // CLEAN ONE-HOT ATC hit with no fault, invisible to the multi-hot fail-safe:
      //   * a TCR.P change re-keying every resident entry (`pageSizeRekey`), and
      //   * a PFLUSHA landing on an already-launched table walk (`flushPoisonArm`),
      //     which under A/UX is every context switch, not the ROM's occasional
      //     `_SwapMMUMode`.
      // These probes answer the only question the fixes leave open: do the
      // preconditions occur at all on a given boot?
      //
      // NOT A CLOCK CROSSING. `dbgCd` is `coreCd.clock` with a different RESET and is
      // `setSynchronousWith(coreCd)` (see above), so a one-cycle core-domain pulse is
      // captured on the same edge. That is why a 1-cycle event cannot be missed here
      // the way it would be across a real CDC.
      //
      // EVERY producer is registered ONCE before it feeds a counter, exactly like the
      // five stall packs above: the only new load on `pageSizeRekey` / `flushPoisonArm`
      // is one flop D-input, and the counters' carry chains start from a flop and end
      // in the read mux. Nothing is added to the ATC lookup cone.
      val mmuRekeyDPulse  = stallDtlb.map(d => RegNext(d.logic.pageSizeRekey) init False)
                                     .getOrElse(False)
      val mmuRekeyIPulse  = stallItlb.map(i => RegNext(i.logic.pageSizeRekey) init False)
                                     .getOrElse(False)
      val mmuPoisonDPulse = stallDtlb.map(d => RegNext(d.logic.flushPoisonArm) init False)
                                     .getOrElse(False)
      val mmuPoisonIPulse = stallItlb.map(i => RegNext(i.logic.flushPoisonArm) init False)
                                     .getOrElse(False)

      // Sticky latches + saturating counts. Cleared ONLY by a write to
      // OFF_MMU_PROBE_CTL bit 0 (`mmuProbeClear`, driven from the WRITE decode -- see
      // the `when(doWrite)` switch, NOT from any read arm) or by the debug POR.
      //
      // A concurrent event WINS over a concurrent clear: the clear is assigned first
      // and the set second, so SpinalHDL's last-assignment-wins leaves the bit set.
      // Evidence is never silently lost by a badly-timed poll.
      // Declared and defaulted HERE, driven True only from the write switch far below.
      // Reading it above its conditional assignment is fine -- this is a netlist, not a
      // program -- and it keeps the clear's single driver inside `when(doWrite)`.
      val mmuProbeClear    = Bool(); mmuProbeClear := False; mmuProbeClear.simPublic()
      val mmuRekeyStickyD  = RegInit(False); mmuRekeyStickyD.simPublic()
      val mmuRekeyStickyI  = RegInit(False); mmuRekeyStickyI.simPublic()
      val mmuPoisonStickyD = RegInit(False); mmuPoisonStickyD.simPublic()
      val mmuPoisonStickyI = RegInit(False); mmuPoisonStickyI.simPublic()
      val mmuRekeyCount    = Reg(UInt(32 bits)) init 0; mmuRekeyCount.simPublic()
      val mmuPoisonCountD  = Reg(UInt(32 bits)) init 0; mmuPoisonCountD.simPublic()
      val mmuPoisonCountI  = Reg(UInt(32 bits)) init 0; mmuPoisonCountI.simPublic()
      /** Saturate rather than wrap: a wrapped counter reading 0 is indistinguishable
        * from "never happened", which is the exact reading this probe exists to make
        * trustworthy. */
      def mmuSatInc(c: UInt, en: Bool): Unit =
        when(en && !c.andR) { c := c + 1 }
      when(mmuProbeClear) {
        mmuRekeyStickyD  := False; mmuRekeyStickyI  := False
        mmuPoisonStickyD := False; mmuPoisonStickyI := False
        mmuRekeyCount    := 0
        mmuPoisonCountD  := 0
        mmuPoisonCountI  := 0
      }
      when(mmuRekeyDPulse)  { mmuRekeyStickyD  := True }
      when(mmuRekeyIPulse)  { mmuRekeyStickyI  := True }
      when(mmuPoisonDPulse) { mmuPoisonStickyD := True }
      when(mmuPoisonIPulse) { mmuPoisonStickyI := True }
      // One TCR.P change pulses BOTH plugins in the same cycle; count it ONCE.
      mmuSatInc(mmuRekeyCount,   mmuRekeyDPulse || mmuRekeyIPulse)
      mmuSatInc(mmuPoisonCountD, mmuPoisonDPulse)
      mmuSatInc(mmuPoisonCountI, mmuPoisonIPulse)

      /** OFF_MMU_PROBE_STATUS.
        *
        *   [0] sticky: a TCR.P change re-keyed the D-side ATC
        *   [1] sticky: a TCR.P change re-keyed the I-side ATC
        *   [2] sticky: a PFLUSHA armed the I-side walk-flush poison
        *   [3] sticky: a PFLUSHA armed the D-side walk-flush poison
        *   [4] live TCR.P (1 = 8 KB pages)
        *   [5] live TC.E (paged translation enabled)
        *   [8] D-side probe PRESENT (a DtlbPlugin is hosted in this build)
        *   [9] I-side probe PRESENT (an ItlbPlugin is hosted in this build)
        *
        * Bits 8/9 are the DEAD-PROBE GUARD and are the reason this register is worth
        * reading when it is otherwise zero. This project has a history of registers
        * that read zero and lie -- `pc_live`, `exc_count` before it was served,
        * `MISPRED`/`FLUSH`, `wedge-status`. With bits 8/9 set, a zero in [3:0] is a
        * MEASUREMENT ("these mechanisms did not fire"); with them clear it is an
        * absence of evidence ("this build has no MMU to probe"), and the two can no
        * longer be confused. */
      val mmuProbeStatus: Bits =
        B(0, 22 bits) ##
        Bool(stallItlb.nonEmpty) ## Bool(stallDtlb.nonEmpty) ##   // [9] [8]
        B(0, 2 bits) ##                                           // [7:6]
        stallDtlb.map(_.logic.mmuEnable).getOrElse(False) ##       // [5]
        stallDtlb.map(_.logic.is8K).getOrElse(False) ##            // [4]
        mmuPoisonStickyD ## mmuPoisonStickyI ##                    // [3] [2]
        mmuRekeyStickyI ## mmuRekeyStickyD                         // [1] [0]

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
      dbgAxi.arready := !arPend && !rPend && !readStage2 && !dbgRst
      dbgAxi.bvalid  := bPend
      dbgAxi.bresp   := DbgAxiLite.RESP_OKAY
      dbgAxi.rvalid  := rPend
      dbgAxi.rdata   := rData
      dbgAxi.rresp   := DbgAxiLite.RESP_OKAY

      when(dbgAxi.awvalid && dbgAxi.awready) {
        awPend := True; awAddr := dbgAxi.awaddr
        // Same edge, same source word -- see `awIsHaltAfter`'s declaration.
        awIsHaltAfter := (dbgAxi.awaddr === DebugRegMap.OFF_HALT_AFTER_LO) ||
                         (dbgAxi.awaddr === DebugRegMap.OFF_HALT_AFTER_HI)
      }
      when(dbgAxi.wvalid  && dbgAxi.wready)  {
        wPend := True; wData := dbgAxi.wdata; wStrb := dbgAxi.wstrb
        // Same edge, same source word -- see `wStrbAny`'s declaration.
        wStrbAny := dbgAxi.wstrb.orR
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
      /** READ STAGE 1. High for exactly one cycle, the cycle in which the captured
        * address is DECODED: the per-region partial words, the live-register physical
        * address and the history-body kind/word are all latched on this edge. Spec
        * section 11 rule 1 ("keep the CSR read mux off any combinational path leaving
        * this block") and spec 3.1 ("the implementation is free of the legacy fixed
        * two-cycle latency") between them allow any read latency this block likes. */
      val doRead = arPend && !rPend && !readStage2
      doRead.simPublic()
      historyReadIssue := doRead && historyBodyRead
      when(doRead) {
        arPend := False
        readStage2 := True
        readIsHistory := historyBodyRead
        if (historyBuilt) when(historyBodyRead) {
          historyReadKind := Mux(pcBodyRead, U(0, 2 bits), Mux(excBodyRead, U(1, 2 bits), U(2, 2 bits)))
          historyReadWord := arAddr(3 downto 2)
          historyReadPcOdd := pcReadIndex(0)
        }
      }
      /** READ STAGE 2. The response word. Exactly one of the six per-region partial
        * words can be non-zero (every `DebugRegMap` offset is unique and every arm of
        * the mux below lists a distinct one), so the OR reproduces the old single
        * `switch(arAddr)` bit for bit -- including its contract that an offset with no
        * arm reads as zero. The history-body and live-integer sources are selected
        * rather than ORed because they are wide/late sources with their own stage-1
        * address capture. */
      when(readStage2) {
        readStage2 := False
        rPend := True
        val historyWord = Bits(DebugRegMap.DBG_DW bits)
        historyWord := B(0, DebugRegMap.DBG_DW bits)
        switch(historyReadKind) {
          is(U(0, 2 bits)) { historyWord := Mux(historyReadPcOdd, pcOddRead, pcEvenRead) }
          is(U(1, 2 bits)) { historyWord := excBodyWord.subdivideIn(32 bits)(historyReadWord) }
          is(U(2, 2 bits)) { historyWord := branchBodyWord.subdivideIn(32 bits)(historyReadWord) }
        }
        rData := Mux(readIsHistory, historyWord,
                 Mux(readIsLiveInt, liveIntWord,
                     rdCore | rdLane | rdCount | rdArch | rdLive | rdHead))
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
      val fetchCheck = fetchFeed.map(f => new FetchWordCheck(f, cpuRstLevel))

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
      // Host-driven clear for the multi-hot evidence latches. A SEPARATE request from
      // `debugClearStickyRequest`: that one clears the halt reason/report latches, and
      // folding the two together would mean every ordinary halt-reason clear silently
      // erased the corruption evidence this register exists to preserve.
      val multiHotClearRequest = if (stage >= 2) RegInit(False) else False
      if (stage >= 2) {
        manualHaltLevel.simPublic()
        debugStopRequest.simPublic(); debugResumeRequest.simPublic(); debugStepRequest.simPublic()
        debugClearStickyRequest.simPublic()
        multiHotClearRequest.simPublic()
        debugStopRequest := False
        debugResumeRequest := False
        debugStepRequest := False
        debugClearStickyRequest := False
        multiHotClearRequest := False
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
      // `awIsHaltAfter` (a flop captured on the same edge as `awAddr`) replaces the
      // pair of 20-bit comparators that used to sit here. Exact substitution -- see its
      // declaration for the cone this was at the head of.
      // `doWrite`'s definition is INLINED here rather than referenced. `doWrite` is one
      // of this block's highest-fanout nets (it enables every arm of the ~60-arm write
      // decode), so reusing it would put this signal -- the head of the design's worst
      // cone -- behind whatever buffering that fanout needs. Written out, the whole
      // expression is five flops into one LUT6.
      val haltAfterInvalidate = if (stage >= 2) {
        awPend && wPend && !bPend && wStrbAny && awIsHaltAfter
      } else False
      if (stage >= 2) haltAfterInvalidate.simPublic()

      // WHY THIS SIGNAL IS NOT ALSO PUT BEHIND A PIPELINE REGISTER (2026-09-17).
      // A whole-group export register -- RegNext on target/epoch/armed/invalidate
      // together -- was built, measured and REVERTED. It is not safe as a pure delay:
      // `RobPlugin.haltAfterConsumed` clears `haltAfterArmed` here, and one extra cycle
      // of exported `armed` lets `RobPlugin.haltAfterDue` stay high for a SECOND cycle
      // after the halt was consumed (`haltAfterComparePending` cannot re-arm in that
      // window, because `haltAfterDue` itself blocks the retire that would set it).
      // `haltAfterDue` drives `debugAutomaticBoundaryHit` -> `debugStopBoundaryHit` ->
      // `debugRecoverEnter` -> `doFlushReg`, so a second cycle of it is a second debug
      // flush pulse -- a behaviour change, in the halt path, for a timing gain of one
      // LUT. `DebugCtrlCsrSpec`'s halt-after one-shot test caught the phase change.
      // The right place to break this cone is the ROB side (register `haltAfterDue` /
      // `haltAfterRetireBlock` off the retire gate), which is RobPlugin's to own.

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

      // ── LIVE integer registers: the committed-RAT walk, now PIPELINED ────────────
      // This is the single longest thing `arAddr` used to reach. The chain was
      //   arAddr -> range compares -> liveIntArch -> committedMap.intPhys[] (a 16-entry
      //   mux over RenameStage's committed RAT) -> debugIntRead.addr -> the integer
      //   physical register file's read decode -> debugIntRead.data -> the ~100-arm CSR
      //   read mux -> rData
      // all in ONE cycle, and it is the concrete form of the back-end finding that
      // DebugCtrlPlugin owns "~950-load nets reaching into the rename RAT".
      //
      // It is now cut in half at the physical-register ADDRESS. Stage 1 does
      // arAddr -> compares -> RAT mux -> flop; stage 2 does PRF read -> flop. Neither
      // half carries the other, and the PRF read no longer feeds the CSR mux at all
      // (`readIsLiveInt` selects it directly in stage 2).
      //
      // SEMANTIC NOTE, deliberately recorded: the RAT index is still sampled in the
      // same cycle as before, but the physical register is now read one cycle later.
      // For the halted core -- which is when a register dump is meaningful, and the
      // only case the REPL's `regs`/`dump` commands use -- nothing moves, so the read
      // is identical. For a RUNNING core this was already an unsynchronised live probe
      // with no coherency guarantee of any kind.
      val liveIntArch = UInt(4 bits)
      liveIntArch := 0
      val liveIntSel = (arAddr >= DebugRegMap.OFF_LIVE_DREG0 && arAddr <= DebugRegMap.OFF_LIVE_DREG7) ||
        (arAddr >= DebugRegMap.OFF_LIVE_AREG0 && arAddr <= DebugRegMap.OFF_LIVE_AREG7) ||
        (arAddr === DebugRegMap.OFF_LIVE_A7)
      when(arAddr >= DebugRegMap.OFF_LIVE_DREG0 && arAddr <= DebugRegMap.OFF_LIVE_DREG7) {
        liveIntArch := ((arAddr - DebugRegMap.OFF_LIVE_DREG0) >> 2).resized
      }
      when(arAddr >= DebugRegMap.OFF_LIVE_AREG0 && arAddr <= DebugRegMap.OFF_LIVE_AREG7) {
        liveIntArch := (((arAddr - DebugRegMap.OFF_LIVE_AREG0) >> 2) + 8).resized
      }
      when(arAddr === DebugRegMap.OFF_LIVE_A7) { liveIntArch := 15 }
      when(doRead) { readIsLiveInt := liveIntSel }
      if (debugIntRead != null) {
        val liveIntPhysAddr = Reg(UInt(debugIntRead.addr.getWidth bits)) init 0
        liveIntPhysAddr.simPublic()
        when(doRead) {
          liveIntPhysAddr := committedMap.map { map =>
            map.intPhys(liveIntArch.resize(log2Up(map.intPhys.length))).resized
          }.getOrElse(U(0, debugIntRead.addr.getWidth bits))
        }
        debugIntRead.addr := liveIntPhysAddr
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

      // ── Read mux: a TWO-STAGE pipeline (FMax, 2026-09-17) ─────────────────────────
      // Stage 1's CSR values are added by Tasks 8-10. The default arm is the whole
      // contract for every offset this stage does not implement: spec 3.2 -- "It must
      // reserve them, return zero for absent functions, and never repurpose them."
      //
      // WHY THIS IS SPLIT. The mux used to be ONE `switch(arAddr)` with ~100 arms whose
      // result landed directly in `rData`, i.e. a ~20-bit comparison against ~100
      // constants followed by a ~100:1 32-bit mux, in a single cycle -- the
      // `csr_arAddr_reg => csr_rData_reg` family (23 endpoints, worst -0.208 ns at
      // 200 MHz). Worse, several of its SOURCES are themselves late combinational packs
      // of live core state (`DcachePlugin.dbgStallDcPack`, `LsEuPlugin.dbgStallGrantPack`
      // -- see their registered copies above) or, in the case of the live integer
      // registers, a walk through the committed RAT into a physical-register-file read.
      //
      // The mux is now partitioned by ADDRESS REGION -- the regions the register map
      // already has -- with one registered partial word per region. Exactly one region
      // can match a given address (every offset in `DebugRegMap` is unique and every arm
      // below lists a distinct one), and a region that does not match holds zero, so
      // stage 2's OR of the six partial words reproduces the old single `switch`
      // EXACTLY, including its "unlisted offset reads zero" contract.
      //
      // Cost: 6 x 32 flops, and reads take one more cycle to answer (RVALID one cycle
      // later). Debug CSR reads arrive over JTAG at human speed and every driver in the
      // tree -- `DbgAxiDriver`, `tools/jtag_repl.tcl`, the Vivado JTAG-to-AXI master --
      // is handshake-driven, never fixed-latency. History-body reads ALREADY took this
      // extra cycle (`historyReadPending`); this change simply makes every read uniform.
      when(doRead) {
        // ── Region 0: 0x00000-0x000FF, identity / control / halt / SoC config ───────
        rdCore := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          is(DebugRegMap.OFF_VERSION) {
            rdCore := B(DebugRegMap.VERSION_VALUE, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_BUILD_ID) {
            rdCore := B(buildId, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_FEATURES) {
            // NOT a literal: computed from the STAGE column of debug_regmap.def, so a
            // build cannot advertise a bit whose behaviour it has not built (spec 3.4).
            rdCore := B(advertisedFeatures, DebugRegMap.DBG_DW bits)
          }
          is(DebugRegMap.OFF_CAP_TRACE) {
            val depth = if (historyBuilt) historyDepth else 0
            rdCore := B(depth, 16 bits) ## B(depth, 16 bits)
          }
          is(DebugRegMap.OFF_CAP_TRACE2) {
            val depth = if (historyBuilt) historyDepth else 0
            rdCore := B(0, 16 bits) ## B(depth, 16 bits)
          }
          is(DebugRegMap.OFF_CONTROL) {
            rdCore := controlWord
          }
          is(DebugRegMap.OFF_PC)      { rdCore := livePc.asBits }
          is(DebugRegMap.OFF_LAST_PC) { rdCore := lastPc.asBits }
          is(DebugRegMap.OFF_HALT_AFTER_LO) { rdCore := haltAfterTarget(31 downto 0).asBits }
          is(DebugRegMap.OFF_HALT_AFTER_HI) { rdCore := haltAfterTarget(63 downto 32).asBits }
          if (stage >= 5) {
            is(DebugRegMap.OFF_BREAK_PC0) { rdCore := breakPc(0).asBits }
            is(DebugRegMap.OFF_BREAK_PC1) { rdCore := breakPc(1).asBits }
            is(DebugRegMap.OFF_BREAK_PC2) { rdCore := breakPc(2).asBits }
            is(DebugRegMap.OFF_BREAK_PC3) { rdCore := breakPc(3).asBits }
            is(DebugRegMap.OFF_BREAK_PC_CTRL) { rdCore := B(0, 28 bits) ## breakPcEnable }
            is(DebugRegMap.OFF_BP_SKIP_ONCE) { rdCore := B(0, 28 bits) ## breakSkipOnce }
            for (i <- 0 until 8) {
              is(DebugRegMap.OFF_HALT_EXC_MASK0 + i * 4) { rdCore := haltExceptionMask(i) }
            }
          }
          is(DebugRegMap.OFF_HALT_CTL) {
            rdCore := B(0, 31 bits) ## haltAfterArmed
          }
          is(DebugRegMap.OFF_HALT_REASON) {
            rdCore := B(0, 29 bits) ##
              dbgCommit.map(_.haltReasonDebug).getOrElse(U(0, 3 bits)).asBits
          }
          is(DebugRegMap.OFF_HALT_HIT_PC) {
            rdCore := dbgCommit.map(_.haltHitPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_VEC) {
            rdCore := dbgCommit.map(s => s.haltExceptionVector.resize(32).asBits)
              .getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_PC) {
            rdCore := dbgCommit.map(_.haltExceptionPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_EXC_FAULT_ADDR) {
            rdCore := dbgCommit.map(_.haltExceptionFaultAddress.asBits).getOrElse(B(0, 32 bits))
          }
          // 2026-09-09: these two have been RESERVED in the regmap since Stage 1 with
          // nothing driving them, which is why the REPL always printed `dbl_fault=0` even
          // on a core that had double-faulted. Now backed by `ExceptionUnit.dblFaultPc` /
          // `dblFaultVec` -- the PC of the instruction whose exception processing hit a
          // bus error on its handler-vector read, and the vector it was fetching (the
          // faulting table address is VBR + vec*4). Both stay 0 until a double fault.
          is(DebugRegMap.OFF_DBL_FAULT_PC) {
            rdCore := dbgCommit.map(_.dblFaultPc.asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_DBL_FAULT_VEC) {
            rdCore := dbgCommit.map(s => s.dblFaultVec.resize(32).asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_HALT_HIT_INST_LO) {
            rdCore := haltHitInstCount(31 downto 0).asBits
          }
          is(DebugRegMap.OFF_HALT_HIT_INST_HI) {
            rdCore := haltHitInstCount(63 downto 32).asBits
          }
          is(DebugRegMap.OFF_STATUS) {
            rdCore := B(0, 27 bits) ##
                      automaticHalt ##    // bit 4  auto-halt latched
                      !effectiveHalt ##   // bit 3  running (inverse of halted)
                      initDoneLatched ##  // bit 2  init-done seen
                      dbgCommit.map(_.exceptionPending).getOrElse(False) ## // bit 1
                      effectiveHalt       // bit 0  effective coherent halt
          }
          is(DebugRegMap.OFF_RAM_WINDOW_LG2) { rdCore := ramWindowWord }
          is(DebugRegMap.OFF_MON_SENSE)      { rdCore := monSenseWord }
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            // Deployed layout (debug_ctrl.v:1411): {cpu_reset_count_r, 16'd0}.
            rdCore := cpuResetCount.asBits ## B(0, 16 bits)
          }
        }

        // ── Region 1: 0x00100-0x00FFF + 0x00200 block, halt lanes / cache maint ─────
        rdLane := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          if (stage >= 5) {
            is(DebugRegMap.OFF_A7ODD_CTL) { rdLane := a7OddCtl }
            is(DebugRegMap.OFF_PCRANGE_CTL) { rdLane := pcRangeCtl }
            is(DebugRegMap.OFF_PCRANGE_LO)  { rdLane := pcRangeLo }
            is(DebugRegMap.OFF_PCRANGE_HI)  { rdLane := pcRangeHi }
            is(DebugRegMap.OFF_PCRANGE_PC0) {
              rdLane := dbgCommit.map(_.pcRangePc0.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_PC1) {
              rdLane := dbgCommit.map(_.pcRangePc1.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_PC2) {
              rdLane := dbgCommit.map(_.pcRangePc2.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_PCRANGE_COUNT) {
              rdLane := dbgCommit.map(s => B(0, 16 bits) ## s.pcRangeCount.asBits)
                .getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC0) {
              rdLane := dbgCommit.map(_.a7OddPc0.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC1) {
              rdLane := dbgCommit.map(_.a7OddPc1.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_PC2) {
              rdLane := dbgCommit.map(_.a7OddPc2.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_VALUE) {
              rdLane := dbgCommit.map(_.a7OddValue.asBits).getOrElse(B(0, 32 bits)) }
            is(DebugRegMap.OFF_A7ODD_COUNT) {
              rdLane := dbgCommit.map(s => B(0, 16 bits) ## s.a7OddEpisodes.asBits)
                .getOrElse(B(0, 32 bits)) }
          }
          // 2026-09-09: the fatal-halt ATTRIBUTION. `OFF_HALT_REASON` reports the debug
          // domain's coarse code and collapses every fatal cause to FATAL(4); this is
          // `RobPlugin.haltReason`, the sticky first-wins `socket.HaltReason` value that
          // says WHICH producer halted the core. Without it a fatal halt on hardware is
          // unattributable without an ENABLE_ILA bitstream.
          is(DebugRegMap.OFF_HALT_KIND) {
            rdLane := dbgCommit.map(s => s.haltKind.resize(32).asBits).getOrElse(B(0, 32 bits))
          }
          is(DebugRegMap.OFF_DCACHE_OP) {
            rdLane := B(0, 26 bits) ## cacheOpError ## cacheOpRejected ##
              cacheOpSel.asBits ## cacheOpDone ## cacheOpBusy
          }
          is(DebugRegMap.OFF_ICACHE_OP) {
            rdLane := B(0, 26 bits) ## cacheOpError ## cacheOpRejected ##
              cacheOpSel.asBits ## cacheOpDone ## cacheOpBusy
          }
        }

        // ── Region 2: 0x01000-0x01FFF, free-running counters and stall packs ────────
        rdCount := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          // Free-running core_clk cycle count -- see cycleCountReg above. This is
          // the frequency probe; it advances whenever the clock does, halted or
          // not, so a pair of reads a known interval apart MEASURES the core
          // clock instead of restating what the build asked for.
          is(DebugRegMap.OFF_CYCLE_LO) { rdCount := cycleCount(31 downto 0).asBits }
          is(DebugRegMap.OFF_CYCLE_HI) { rdCount := cycleCount(63 downto 32).asBits }
          is(DebugRegMap.OFF_INST_LO) { rdCount := macroCount(31 downto 0).asBits }
          fetchCheck.foreach { f =>
            is(DebugRegMap.OFF_FETCH_CHECK_CTL) {
              rdCount := B(0, 29 bits) ## f.slot ## f.hit ## f.enabled
            }
            is(DebugRegMap.OFF_FETCH_CHECK_PC) { rdCount := f.pc.asBits }
            is(DebugRegMap.OFF_FETCH_CHECK_WORD) { rdCount := f.expected.resize(32) }
            is(DebugRegMap.OFF_FETCH_CHECK_HIT_PC) { rdCount := f.hitPc.asBits }
            is(DebugRegMap.OFF_FETCH_CHECK_HIT_WORD) { rdCount := f.hitWord }
            is(DebugRegMap.OFF_FETCH_CHECK_SEEN) { rdCount := f.seen.asBits }
          }
          is(DebugRegMap.OFF_INST_HI) { rdCount := macroCount(63 downto 32).asBits }
          // Live, free-running, never halt-captured -- see excCountReg above.
          is(DebugRegMap.OFF_EXC_COUNT) { rdCount := excCount }
          // ── p141 walker-stall state (live; no halt required) ──────────────────
          // Read these ALONGSIDE OFF_INST_LO/HI: the wedge is identified by the
          // retire count being frozen, and these four words say what it is frozen
          // ON. See DebugRegMap's OFF_STALL_* comment for why they live here in
          // the counter block rather than the halt-captured arch block.
          // The four packs are read from their REGISTERED copies (see above): they are
          // deep combinational packs of live core state and belong nowhere near a mux.
          is(DebugRegMap.OFF_STALL_ARB)   { rdCount := stallArbPackReg }
          is(DebugRegMap.OFF_STALL_DC)    { rdCount := stallDcPackReg }
          is(DebugRegMap.OFF_STALL_GRANT) { rdCount := stallGrantPackReg }
          is(DebugRegMap.OFF_STALL_EXC)   { rdCount := stallExcPackReg }
          is(DebugRegMap.OFF_STALL_WALK)  { rdCount := stallWalkPackReg }
          // Multi-hot evidence (2026-09-17). Region 2 so it is LIVE -- no halt required
          // -- for the same reason the p141 stall words are: a multi-hot may well be part
          // of why a halt never lands.
          is(DebugRegMap.OFF_MULTIHOT_STATUS)   { rdCount := multiHotStatusReg }
          is(DebugRegMap.OFF_MULTIHOT_IC)       { rdCount := multiHotAddrRegs(0) }
          is(DebugRegMap.OFF_MULTIHOT_DCLOAD)   { rdCount := multiHotAddrRegs(1) }
          is(DebugRegMap.OFF_MULTIHOT_DCPROBE)  { rdCount := multiHotAddrRegs(2) }
          is(DebugRegMap.OFF_MULTIHOT_DCSTORE)  { rdCount := multiHotAddrRegs(3) }
          is(DebugRegMap.OFF_MULTIHOT_ITLB)     { rdCount := multiHotAddrRegs(4) }
          is(DebugRegMap.OFF_MULTIHOT_DTLB)     { rdCount := multiHotAddrRegs(5) }
          // ── Windowed performance counters (2026-09-17) ────────────────────────
          // Region 2 so they are LIVE -- no halt required. A halt would itself change
          // the numbers, which for a performance counter is the one thing that must not
          // happen; the FREEZE bit in OFF_PERF_CTL is how a window is made read-stable,
          // not a halt.
          //
          // OFF_MISPRED_COUNT / OFF_FLUSH_COUNT were DECLARED at Stage 1 and never
          // driven ("zero until a real producer exists"). They are served here. Their
          // offsets do not move: a host that has been reading them all along starts
          // getting truth from the same address.
          is(DebugRegMap.OFF_MISPRED_COUNT)     { rdCount := perfMispred.asBits }
          is(DebugRegMap.OFF_FLUSH_COUNT)       { rdCount := perfFlush.asBits }
          is(DebugRegMap.OFF_PERF_CTL)          { rdCount := perfCtlWord }
          is(DebugRegMap.OFF_PERF_CYCLE_LO)     { rdCount := perfCycle(31 downto 0).asBits }
          is(DebugRegMap.OFF_PERF_CYCLE_HI)     { rdCount := perfCycle(63 downto 32).asBits }
          is(DebugRegMap.OFF_PERF_INST_LO)      { rdCount := perfInst(31 downto 0).asBits }
          is(DebugRegMap.OFF_PERF_INST_HI)      { rdCount := perfInst(63 downto 32).asBits }
          is(DebugRegMap.OFF_PERF_BRANCH)       { rdCount := perfBranch.asBits }
          is(DebugRegMap.OFF_PERF_DC_MISS)      { rdCount := perfDcMiss.asBits }
          is(DebugRegMap.OFF_PERF_IC_MISS)      { rdCount := perfIcMiss.asBits }
          is(DebugRegMap.OFF_PERF_DTLB_WALK)    { rdCount := perfDtlbWalk.asBits }
          is(DebugRegMap.OFF_PERF_ITLB_WALK)    { rdCount := perfItlbWalk.asBits }
          is(DebugRegMap.OFF_PERF_STALL_RETIRE) { rdCount := perfStallRetire.asBits }
          is(DebugRegMap.OFF_PERF_STALL_DC)     { rdCount := perfStallDc.asBits }
          is(DebugRegMap.OFF_PERF_STALL_WALK)   { rdCount := perfStallWalk.asBits }
          is(DebugRegMap.OFF_PERF_DETAIL_CAP) { rdCount := B(detailCap, 32 bits) }
          for ((counter, index) <- detailCounters.zipWithIndex) {
            is(DebugRegMap.OFF_PERF_DETAIL_BASE + index * 4) { rdCount := counter.asBits }
          }
          // ── MMU translation-key probes (live; no halt required) ───────────────
          // OFF_MMU_PROBE_CTL is WRITE-ONLY (W1P) and deliberately has NO read arm:
          // it reads back as zero from the region default, which is what the map
          // promises. Its clear is decoded in the WRITE switch below -- putting a
          // clear in a read switch elaborates cleanly, reads back perfectly and does
          // nothing, which is the exact trap this comment exists to flag.
          is(DebugRegMap.OFF_MMU_PROBE_STATUS)  { rdCount := mmuProbeStatus }
          is(DebugRegMap.OFF_MMU_REKEY_COUNT)   { rdCount := mmuRekeyCount.asBits }
          is(DebugRegMap.OFF_MMU_IPOISON_COUNT) { rdCount := mmuPoisonCountI.asBits }
          is(DebugRegMap.OFF_MMU_DPOISON_COUNT) { rdCount := mmuPoisonCountD.asBits }
        }

        // ── Region 3: 0x02000-0x020FF, halted architectural write shadows ───────────
        rdArch := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          for (i <- 0 until 8) {
            is(DebugRegMap.OFF_ARCH_D0 + i * 4) { rdArch := archWord(i) }
            is(DebugRegMap.OFF_ARCH_A0 + i * 4) { rdArch := archWord(8 + i) }
          }
          is(DebugRegMap.OFF_ARCH_USP)  { rdArch := archWord(16) }
          is(DebugRegMap.OFF_ARCH_SSP)  { rdArch := archWord(17) }
          is(DebugRegMap.OFF_ARCH_ISP)  { rdArch := archWord(18) }
          is(DebugRegMap.OFF_ARCH_SR)   { rdArch := archWord(19) }
          is(DebugRegMap.OFF_ARCH_VBR)  { rdArch := archWord(20) }
          is(DebugRegMap.OFF_ARCH_CACR) { rdArch := archWord(21) }
          is(DebugRegMap.OFF_ARCH_TC)   { rdArch := archWord(22) }
          is(DebugRegMap.OFF_ARCH_ITT0) { rdArch := archWord(23) }
          is(DebugRegMap.OFF_ARCH_ITT1) { rdArch := archWord(24) }
          is(DebugRegMap.OFF_ARCH_DTT0) { rdArch := archWord(25) }
          is(DebugRegMap.OFF_ARCH_DTT1) { rdArch := archWord(26) }
          is(DebugRegMap.OFF_ARCH_URP)  { rdArch := archWord(27) }
          is(DebugRegMap.OFF_ARCH_SRP)  { rdArch := archWord(28) }
          is(DebugRegMap.OFF_ARCH_PC)   { rdArch := archWord(29) }
          is(DebugRegMap.OFF_ARCH_SFC)  { rdArch := archWord(30) }
          is(DebugRegMap.OFF_ARCH_DFC)  { rdArch := archWord(31) }
          is(DebugRegMap.OFF_ARCH_APPLY) {
            rdArch := B(0, 31 bits) ## archApplyBusy
          }
          is(DebugRegMap.OFF_ARCH_STATUS) {
            rdArch := B(0, 29 bits) ## archApplyRejected ## archApplyDone ## archApplyBusy
          }
        }

        // ── Region 4: 0x02100-0x02FFF, LIVE architectural state ─────────────────────
        // The integer-register offsets (OFF_LIVE_A7, OFF_LIVE_DREG*, OFF_LIVE_AREG*)
        // are deliberately ABSENT here: they are answered by the registered PRF read
        // (`readIsLiveInt` / `liveIntPhysAddr`), which is what takes the committed-RAT
        // walk and the physical-register-file read off `arAddr`'s combinational cone.
        rdLive := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          is(DebugRegMap.OFF_LIVE_VBR)   { rdLive := dbgSystem.map(_.vbr.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SR)    { rdLive := dbgSystem.map(s => s.sr.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_USP)   { rdLive := dbgSystem.map(_.usp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_TC)   { rdLive := dbgSystem.map(_.tc.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_DTT0) { rdLive := dbgSystem.map(_.dtt0.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_DTT1) { rdLive := dbgSystem.map(_.dtt1.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_ITT0) { rdLive := dbgSystem.map(_.itt0.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_ITT1) { rdLive := dbgSystem.map(_.itt1.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_SRP)  { rdLive := dbgSystem.map(_.srp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_MMU_URP)  { rdLive := dbgSystem.map(_.urp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SSP)   { rdLive := dbgSystem.map(_.msp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_ISP)   { rdLive := dbgSystem.map(_.isp.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_CACR)  { rdLive := dbgSystem.map(_.cacr.asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_SFC)   { rdLive := dbgSystem.map(s => s.sfc.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_DFC)   { rdLive := dbgSystem.map(s => s.dfc.resize(32).asBits).getOrElse(B(0, 32 bits)) }
          is(DebugRegMap.OFF_LIVE_PC)    { rdLive := livePc.asBits }
          is(DebugRegMap.OFF_LIVE_MMUSR) { rdLive := dbgSystem.map(_.mmusr.asBits).getOrElse(B(0, 32 bits)) }
        }

        // ── Region 5: 0x11000 / 0x13000 / 0x15000, forensic ring HEADS ──────────────
        rdHead := B(0, DebugRegMap.DBG_DW bits)
        switch(arAddr) {
          is(DebugRegMap.OFF_PC_TRACE_HEAD) {
            rdHead := pcTraceHead.resize(32).asBits
          }
          is(DebugRegMap.OFF_EXC_RING_HEAD) {
            rdHead := excRingHead.resize(32).asBits
          }
          is(DebugRegMap.OFF_BRANCH_RING_HEAD) {
            rdHead := branchRingHead.resize(32).asBits
          }
        }
      }

      // ── Write decode ──────────────────────────────────────────────────────────────
      // Writes to anything this stage does not implement are dropped with an OKAY
      // response, which the FSM above already produces unconditionally.
      when(doWrite) {
        switch(awAddr) {
          fetchCheck.foreach { f =>
            is(DebugRegMap.OFF_FETCH_CHECK_CTL) {
              when(wStrb(0)) { f.enabled := wData(0); f.clear := wData(1) }
            }
            is(DebugRegMap.OFF_FETCH_CHECK_PC) { f.pc := merged(f.pc.asBits).asUInt }
            is(DebugRegMap.OFF_FETCH_CHECK_WORD) {
              f.expected := merged(f.expected.resize(32))(15 downto 0)
            }
          }
          // Clear the multi-hot evidence latches. Modelled on OFF_HALT_CTL bit 2 (the
          // sticky reason/report clear): one write-strobe-qualified data bit, no
          // read-modify-write, and the rest of the word -- which is the read-side
          // status -- is ignored on write.
          //
          // THIS ARM BELONGS IN THIS SWITCH AND NOWHERE ELSE. It was first written into
          // a READ-region `switch(arAddr)` by mistake, where it elaborated cleanly,
          // emitted no warning, and simply never fired: the CSR read back correctly and
          // the clear silently did nothing. That is the exact "one arm dropped into the
          // wrong region" failure this file's read-mux comment describes, and it was
          // caught only because DebugCtrlMultiHotSpec tests the clear rather than
          // assuming it.
          is(DebugRegMap.OFF_MULTIHOT_STATUS) {
            if (stage >= 2) when(wStrb(0)) {
              when(wData(0)) { multiHotClearRequest := True }
            }
          }
          // Windowed performance counters (2026-09-17). SAME SWITCH, SAME REASON as
          // the multi-hot clear directly above: this is `switch(awAddr)` inside
          // `when(doWrite)`, the WRITE decode. An arm placed in a read-region
          // `switch(arAddr)` elaborates cleanly, warns about nothing, reads back
          // perfectly and never fires. That is not hypothetical here -- it is what
          // happened to the multi-hot clear on 2026-09-17, and it was caught only
          // because the spec TESTED the clear instead of assuming it.
          //
          // Byte 0 carries both controls, so one strobed write sets both:
          //   0x3  CLEAR + RUN   -- zero everything and start a fresh window
          //   0x0  RUN=0         -- FREEZE; now the 64-bit LO/HI pairs read atomically
          //   0x2  RUN=1         -- resume without zeroing
          //   0x1  CLEAR, RUN=0  -- zero and stay frozen
          // RUN is taken from the written bit unconditionally (no read-modify-write):
          // the host always states the run state it wants, so a clear cannot silently
          // leave the counters frozen or running against its intent.
          is(DebugRegMap.OFF_PERF_CTL) {
            if (stage >= 2) when(wStrb(0)) {
              perfRun := wData(1)
              when(wData(0)) { perfClear := True }
            }
          }
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
          // OFF_MMU_PROBE_CTL: W1P clear for the four MMU translation-key probe
          // registers. THIS IS THE WRITE SWITCH (`when(doWrite) { switch(awAddr) ... }`)
          // -- the clear has to be here and nowhere else.
          is(DebugRegMap.OFF_MMU_PROBE_CTL) {
            when(wStrb(0) && wData(0)) { mmuProbeClear := True }
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
        fetchCheck.foreach { f =>
          f.enabled := False; f.pc := 0; f.expected := 0; f.clear := True
        }
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
      // Route the multi-hot clear back to every producer. `&& !dbgRst` mirrors every
      // other request above: the CPU domain can leave reset before this reset-less
      // debug POR generator finishes, and a transient must never wipe the evidence.
      // Direct assignment into each plugin's `allowOverride` default-False wire, the
      // same reach-in this file already uses to READ their packs; `dbgCd` shares
      // `coreCd.clock` (only the reset differs), so this is not a clock crossing.
      val mhClear = csr.multiHotClearRequest && !dbgRst
      mhIcache.foreach(_.logic.dbgMultiHotClear := mhClear)
      stallDcache.foreach(_.logic.dbgMultiHotClear := mhClear)
      stallItlb.foreach(_.logic.dbgMultiHotClear := mhClear)
      stallDtlb.foreach(_.logic.dbgMultiHotClear := mhClear)
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
