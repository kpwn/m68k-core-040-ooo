package m68k040.debug

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
  * ==No new services, no new `Global` key==
  * Spec section 0.9: "Add no `Global` database key for debug. Parameters are
  * constructor/config values." Stage 1 also adds no service trait: it has no cross-plugin
  * consumer, and every port it needs is core-level IO it declares itself, exactly as
  * `DcachePlugin` declares its `Axi4` master. The first real seam is Stage 2's
  * `DebugCommitService`, provided by `RobPlugin`.
  *
  * @param buildId   SoC-supplied build identity read back at `OFF_BUILD_ID` (spec 3.1:
  *                  "`DBG_BUILD_ID` remains supplied by the SoC build").
  * @param porCycles Length of the debug power-on reset in core clocks.
  * @param stage     Implementation stage this build has actually reached. Drives
  *                  `OFF_FEATURES` through `DebugRegMap.featuresForStage`, which is the
  *                  executable form of spec section 3.4's honesty rule. */
class DebugCtrlPlugin(val buildId:   BigInt = BigInt(0),
                      val porCycles: Int    = DebugRegMap.POR_CYCLES_DEFAULT,
                      val stage:     Int    = 1) extends FiberPlugin {
  require(porCycles >= 1, s"DebugCtrlPlugin: porCycles must be >= 1 (got $porCycles)")
  require(stage >= 1, s"DebugCtrlPlugin: stage must be >= 1 (got $stage)")
  require(buildId >= 0 && buildId < (BigInt(1) << DebugRegMap.DBG_DW),
    s"DebugCtrlPlugin: buildId must fit ${DebugRegMap.DBG_DW} bits (got $buildId)")

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

    val csr = dbgCd on new Area {
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

      dbgAxi.awready := !awPend && !bPend && !dbgRst
      dbgAxi.wready  := !wPend  && !bPend && !dbgRst
      dbgAxi.arready := !arPend && !rPend && !dbgRst
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

      when(dbgAxi.arvalid && dbgAxi.arready) { arPend := True; arAddr := dbgAxi.araddr }
      /** High for exactly one cycle, the cycle in which the read mux result is latched.
        * Registering the result is deliberate: spec section 11 rule 1 keeps the CSR read
        * mux off any combinational path leaving this block, and spec 3.1 explicitly frees
        * the implementation from the legacy fixed two-cycle latency. */
      val doRead = arPend && !rPend
      doRead.simPublic()
      when(doRead)                  { arPend := False; rPend := True }
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
      val cpuRstLevel = coreCd.isResetActive
      val cpuRstQ     = RegInit(True)
      cpuRstQ := cpuRstLevel
      val cpuRstEvent = cpuRstLevel && !cpuRstQ; cpuRstEvent.simPublic()

      /** Surviving 16-bit count of observed CPU-reset edges. SATURATES: zero means "no
        * reset observed since the last clear" and must not be reachable by wraparound
        * (spec 15.4). Lives in the debug domain, so it survives the resets it counts. */
      val cpuResetCount = Reg(UInt(16 bits)) init 0; cpuResetCount.simPublic()
      when(cpuRstEvent && cpuResetCount =/= U(0xFFFF, 16 bits)) {
        cpuResetCount := cpuResetCount + 1
      }

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

      /** The CONTROL word exactly as the host reads it back. Defined ONCE and used by
        * both the read mux and the write-side byte-strobe merge, so the two can never
        * disagree about a bit's position or its RAZ/WI status. */
      def controlWord: Bits =
        B(0, 24 bits) ##
        False ##            // bit 7  legacy step-arm      -- RAZ/WI until Stage 2
        False ##            // bit 6  reserved             -- always 0 (spec 3.3)
        coldPulse ##        // bit 5  cold-reset pulse
        ctrlColdHold ##     // bit 4  cold-reset hold level
        ctrlInitDoneOvr ##  // bit 3  init-done override level
        coldPulse ##        // bit 2  deprecated pulse alias of bit 5
        False ##            // bit 1  single-step pulse    -- RAZ/WI until Stage 2
        False               // bit 0  manual halt request  -- RAZ/WI until Stage 2

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
            rData := B(DebugRegMap.featuresForStage(stage), DebugRegMap.DBG_DW bits)
          }
          // OFF_CAP_TRACE is deliberately absent: Stage 1 has no trace memories, so it
          // reads the reserved zero above, which is exactly what feature bits 4/5 being
          // clear promises (spec section 9.3).
          is(DebugRegMap.OFF_CONTROL) {
            rData := controlWord
          }
          is(DebugRegMap.OFF_STATUS) {
            rData := B(0, 27 bits) ##
                     False ##            // bit 4  auto-halt latched    (Stage 2)
                     True ##             // bit 3  running -- always, there is no halt yet
                     initDoneLatched ##  // bit 2  init-done seen
                     False ##            // bit 1  exception pending    (Stage 2)
                     False               // bit 0  halted               (Stage 2)
          }
          is(DebugRegMap.OFF_RAM_WINDOW_LG2) { rData := ramWindowWord }
          is(DebugRegMap.OFF_MON_SENSE)      { rData := monSenseWord }
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            // Deployed layout (debug_ctrl.v:1411): {cpu_reset_count_r, 16'd0}.
            rData := cpuResetCount.asBits ## B(0, 16 bits)
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
            // host reads back, so an unstrobed byte provably cannot change a bit. Bits 0
            // (halt), 1 (step) and 7 (step-arm) are absent from `controlWord`, so they
            // merge as 0 and are never stored -- spec 15.2 chooses RAZ/WI over a level
            // that reads back as if a halt were coming.
            val m = merged(controlWord)
            ctrlInitDoneOvr := m(3)
            ctrlColdHold    := m(4)
            // Bit 2 is the deprecated reset-pulse ALIAS of bit 5 (spec 3.3).
            coldPulse       := m(5) || m(2)
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
        }
      }

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
      // MAINTENANCE RULE: any future statement assigning `ctrlInitDoneOvr` or
      // `initDoneSticky` must be placed ABOVE this block, never below it. Task 10's and
      // Task 11's write-decode additions all go inside the `when(doWrite)` switch above,
      // so they are; Task 10's `cfgWipe` block is a PEER of this one, placed after the
      // write decode for exactly the same last-wins reason, and assigns a disjoint set of
      // registers (`ramWindow`, `mon`), so the order between those two blocks is free.
      when(cpuRstEvent) {
        ctrlInitDoneOvr := False
        initDoneSticky  := False
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
        assert(!(cpuRstLevel && !doWrite && !cfgWipe && (cfgVec =/= cfgVecPrev)),
          "DebugCtrlPlugin: surviving debug configuration changed while CPU reset was " +
          "asserted (spec section 13)",
          FAILURE)
      }

      coldResetPulse := coldPulse
      coldResetHold  := ctrlColdHold
      ramWindowLg2   := ramWindow
      monSense       := mon
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
