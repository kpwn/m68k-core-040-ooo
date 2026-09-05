// GENERATED FILE -- DO NOT EDIT BY HAND.
// Source:     tools/debug/debug_regmap.def
// Regenerate: python3 tools/debug/gen_debug_regmap.py
// Verify:     python3 tools/debug/gen_debug_regmap.py --check

package m68k040.debug

/** Frozen offsets, capability bitmap, constants and socket ports of the
  * CPU-side `dbg_axi` debug/control slave. See the design spec
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`
  * sections 3.2/3.4 and 15. */
object DebugRegMap {
  // -- constants --------------------------------------------------------
  val DBG_AW: Int = 20
  val DBG_DW: Int = 32
  val MON_SENSE_POR: Int = 6
  val POR_CYCLES_DEFAULT: Int = 16
  val RAM_WINDOW_LG2_MAX: Int = 30
  val RAM_WINDOW_LG2_MIN: Int = 22
  val RAM_WINDOW_LG2_POR: Int = 26
  val VERSION_VALUE: BigInt = BigInt("DEB60100", 16)

  // -- offsets ----------------------------------------------------------
  val OFF_VERSION: Int = 0x00000
  val OFF_BUILD_ID: Int = 0x00004
  val OFF_CONTROL: Int = 0x00008
  val OFF_STATUS: Int = 0x0000C
  val OFF_PC: Int = 0x00010
  val OFF_LAST_PC: Int = 0x00014
  val OFF_REDIRECT_PC: Int = 0x00018
  val OFF_REDIRECT_TRIGGER: Int = 0x0001C
  val OFF_IRQ_INJECT: Int = 0x00020
  val OFF_EXC_VEC: Int = 0x00024
  val OFF_EXC_PC: Int = 0x00028
  val OFF_RESET_CAUSE: Int = 0x0002C
  val OFF_HALT_AFTER_LO: Int = 0x00030
  val OFF_HALT_AFTER_HI: Int = 0x00034
  val OFF_BREAK_PC0: Int = 0x00038
  val OFF_HALT_CTL: Int = 0x0003C
  val OFF_HALT_REASON: Int = 0x00040
  val OFF_HALT_HIT_PC: Int = 0x00044
  val OFF_HALT_HIT_INST_LO: Int = 0x00048
  val OFF_HALT_HIT_INST_HI: Int = 0x0004C
  val OFF_HALT_EXC_VEC: Int = 0x00050
  val OFF_EXC_FAULT_ADDR: Int = 0x00054
  val OFF_RAM_WINDOW_LG2: Int = 0x00058
  val OFF_MON_SENSE: Int = 0x0005C
  val OFF_HALT_EXC_MASK0: Int = 0x00060
  val OFF_HALT_EXC_MASK1: Int = 0x00064
  val OFF_HALT_EXC_MASK2: Int = 0x00068
  val OFF_HALT_EXC_MASK3: Int = 0x0006C
  val OFF_HALT_EXC_MASK4: Int = 0x00070
  val OFF_HALT_EXC_MASK5: Int = 0x00074
  val OFF_HALT_EXC_MASK6: Int = 0x00078
  val OFF_HALT_EXC_MASK7: Int = 0x0007C
  val OFF_BP_SKIP_ONCE: Int = 0x00080
  val OFF_BREAK_PC1: Int = 0x00084
  val OFF_BREAK_PC2: Int = 0x00088
  val OFF_BREAK_PC3: Int = 0x0008C
  val OFF_BREAK_PC_CTRL: Int = 0x00090
  val OFF_DBL_FAULT_PC: Int = 0x00094
  val OFF_DBL_FAULT_VEC: Int = 0x00098
  val OFF_PC_MISALIGNED_PC: Int = 0x0009C
  val OFF_FEATURES: Int = 0x000A0
  val OFF_DBG_RESET_CTL: Int = 0x000A4
  val OFF_CAP_TRACE: Int = 0x000A8
  val OFF_CAP_TRACE2: Int = 0x000AC
  val OFF_WP0_ADDR: Int = 0x000B0
  val OFF_WP0_AMASK: Int = 0x000B4
  val OFF_WP0_VALUE: Int = 0x000B8
  val OFF_WP0_CTRL: Int = 0x000BC
  val OFF_WP1_ADDR: Int = 0x000C0
  val OFF_WP1_AMASK: Int = 0x000C4
  val OFF_WP1_VALUE: Int = 0x000C8
  val OFF_WP1_CTRL: Int = 0x000CC
  val OFF_WP_HIT: Int = 0x000D0
  val OFF_WP_HIT_ADDR: Int = 0x000D4
  val OFF_WP_HIT_DATA: Int = 0x000D8
  val OFF_WP_HIT_PC: Int = 0x000DC
  val OFF_AT0_CTRL: Int = 0x000E0
  val OFF_AT0_MATCH: Int = 0x000E4
  val OFF_AT0_D0VAL: Int = 0x000E8
  val OFF_AT1_CTRL: Int = 0x000EC
  val OFF_AT1_MATCH: Int = 0x000F0
  val OFF_AT1_D0VAL: Int = 0x000F4
  val OFF_AT_SKIP_ONCE: Int = 0x000F8
  val OFF_AT_HIT: Int = 0x000FC
  val OFF_AT_HIT_PC: Int = 0x00100
  val OFF_AT_HIT_A0: Int = 0x00104
  val OFF_AT_HIT_D0: Int = 0x00108
  val OFF_DCACHE_PROBE_SEL: Int = 0x00200
  val OFF_DCACHE_PROBE_TAG: Int = 0x00204
  val OFF_DCACHE_PROBE_FLAGS: Int = 0x00208
  val OFF_DCACHE_PROBE_DATA: Int = 0x0020C
  val OFF_DCACHE_OP: Int = 0x00210
  val OFF_ICACHE_OP: Int = 0x00214
  val OFF_CYCLE_LO: Int = 0x01000
  val OFF_CYCLE_HI: Int = 0x01004
  val OFF_INST_LO: Int = 0x01008
  val OFF_INST_HI: Int = 0x0100C
  val OFF_MISPRED_COUNT: Int = 0x01010
  val OFF_FLUSH_COUNT: Int = 0x01014
  val OFF_EXC_COUNT: Int = 0x01018
  val OFF_STALL_DC: Int = 0x0101C
  val OFF_STALL_GRANT: Int = 0x01020
  val OFF_STALL_EXC: Int = 0x01024
  val OFF_STALL_WALK: Int = 0x01028
  val OFF_ARCH_D0: Int = 0x02000
  val OFF_ARCH_D1: Int = 0x02004
  val OFF_ARCH_D2: Int = 0x02008
  val OFF_ARCH_D3: Int = 0x0200C
  val OFF_ARCH_D4: Int = 0x02010
  val OFF_ARCH_D5: Int = 0x02014
  val OFF_ARCH_D6: Int = 0x02018
  val OFF_ARCH_D7: Int = 0x0201C
  val OFF_ARCH_A0: Int = 0x02020
  val OFF_ARCH_A1: Int = 0x02024
  val OFF_ARCH_A2: Int = 0x02028
  val OFF_ARCH_A3: Int = 0x0202C
  val OFF_ARCH_A4: Int = 0x02030
  val OFF_ARCH_A5: Int = 0x02034
  val OFF_ARCH_A6: Int = 0x02038
  val OFF_ARCH_A7: Int = 0x0203C
  val OFF_ARCH_USP: Int = 0x02040
  val OFF_ARCH_SSP: Int = 0x02044
  val OFF_ARCH_ISP: Int = 0x02048
  val OFF_ARCH_SR: Int = 0x0204C
  val OFF_ARCH_VBR: Int = 0x02050
  val OFF_ARCH_CACR: Int = 0x02054
  val OFF_ARCH_TC: Int = 0x02058
  val OFF_ARCH_ITT0: Int = 0x0205C
  val OFF_ARCH_ITT1: Int = 0x02060
  val OFF_ARCH_DTT0: Int = 0x02064
  val OFF_ARCH_DTT1: Int = 0x02068
  val OFF_ARCH_URP: Int = 0x0206C
  val OFF_ARCH_SRP: Int = 0x02070
  val OFF_ARCH_PC: Int = 0x02074
  val OFF_ARCH_APPLY: Int = 0x02078
  val OFF_ARCH_STATUS: Int = 0x0207C
  val OFF_ARCH_SFC: Int = 0x02080
  val OFF_ARCH_DFC: Int = 0x02084
  val OFF_LIVE_VBR: Int = 0x02100
  val OFF_LIVE_SR: Int = 0x02104
  val OFF_LIVE_A7: Int = 0x02108
  val OFF_LIVE_USP: Int = 0x0210C
  val OFF_LIVE_DREG0: Int = 0x02110
  val OFF_LIVE_DREG1: Int = 0x02114
  val OFF_LIVE_DREG2: Int = 0x02118
  val OFF_LIVE_DREG3: Int = 0x0211C
  val OFF_LIVE_DREG4: Int = 0x02120
  val OFF_LIVE_DREG5: Int = 0x02124
  val OFF_LIVE_DREG6: Int = 0x02128
  val OFF_LIVE_DREG7: Int = 0x0212C
  val OFF_LIVE_AREG0: Int = 0x02130
  val OFF_LIVE_AREG1: Int = 0x02134
  val OFF_LIVE_AREG2: Int = 0x02138
  val OFF_LIVE_AREG3: Int = 0x0213C
  val OFF_LIVE_AREG4: Int = 0x02140
  val OFF_LIVE_AREG5: Int = 0x02144
  val OFF_LIVE_AREG6: Int = 0x02148
  val OFF_LIVE_AREG7: Int = 0x0214C
  val OFF_LIVE_MMU_TC: Int = 0x02160
  val OFF_LIVE_MMU_DTT0: Int = 0x02164
  val OFF_LIVE_MMU_DTT1: Int = 0x02168
  val OFF_LIVE_MMU_ITT0: Int = 0x0216C
  val OFF_LIVE_MMU_ITT1: Int = 0x02170
  val OFF_LIVE_MMU_SRP: Int = 0x02174
  val OFF_LIVE_MMU_URP: Int = 0x02178
  val OFF_LIVE_SSP: Int = 0x0217C
  val OFF_LIVE_ISP: Int = 0x02180
  val OFF_LIVE_CACR: Int = 0x02184
  val OFF_LIVE_SFC: Int = 0x02188
  val OFF_LIVE_DFC: Int = 0x0218C
  val OFF_LIVE_PC: Int = 0x02190
  val OFF_LIVE_MMUSR: Int = 0x02194
  val OFF_WEDGE0: Int = 0x03000
  val OFF_WEDGE1: Int = 0x03004
  val OFF_WEDGE2: Int = 0x03008
  val OFF_WEDGE3: Int = 0x0300C
  val OFF_FAULT_SNAP_VALID: Int = 0x03010
  val OFF_FAULT_SNAP_W0: Int = 0x03014
  val OFF_FAULT_SNAP_W1: Int = 0x03018
  val OFF_FAULT_SNAP_W2: Int = 0x0301C
  val OFF_FAULT_SNAP_W3: Int = 0x03020
  val OFF_FAULT_SNAP_CLEAR: Int = 0x03024
  val OFF_RTS_SNAP_VALID: Int = 0x03028
  val OFF_RTS_SNAP_W0: Int = 0x0302C
  val OFF_RTS_SNAP_W1: Int = 0x03030
  val OFF_RTS_SNAP_W2: Int = 0x03034
  val OFF_RTS_SNAP_W3: Int = 0x03038
  val OFF_RTS_SNAP_W4: Int = 0x0303C
  val OFF_RTS_SNAP_CLEAR: Int = 0x03040
  val OFF_PC_TRACE_BODY: Int = 0x10000
  val OFF_PC_TRACE_HEAD: Int = 0x11000
  val OFF_EXC_RING_BODY: Int = 0x12000
  val OFF_EXC_RING_HEAD: Int = 0x13000
  val OFF_BRANCH_RING_BODY: Int = 0x14000
  val OFF_BRANCH_RING_HEAD: Int = 0x15000

  /** Every reserved offset: (name, offset), ordered by offset. */
  val allOffsets: Seq[(String, Int)] = Seq(
    ("OFF_VERSION", 0x00000),
    ("OFF_BUILD_ID", 0x00004),
    ("OFF_CONTROL", 0x00008),
    ("OFF_STATUS", 0x0000C),
    ("OFF_PC", 0x00010),
    ("OFF_LAST_PC", 0x00014),
    ("OFF_REDIRECT_PC", 0x00018),
    ("OFF_REDIRECT_TRIGGER", 0x0001C),
    ("OFF_IRQ_INJECT", 0x00020),
    ("OFF_EXC_VEC", 0x00024),
    ("OFF_EXC_PC", 0x00028),
    ("OFF_RESET_CAUSE", 0x0002C),
    ("OFF_HALT_AFTER_LO", 0x00030),
    ("OFF_HALT_AFTER_HI", 0x00034),
    ("OFF_BREAK_PC0", 0x00038),
    ("OFF_HALT_CTL", 0x0003C),
    ("OFF_HALT_REASON", 0x00040),
    ("OFF_HALT_HIT_PC", 0x00044),
    ("OFF_HALT_HIT_INST_LO", 0x00048),
    ("OFF_HALT_HIT_INST_HI", 0x0004C),
    ("OFF_HALT_EXC_VEC", 0x00050),
    ("OFF_EXC_FAULT_ADDR", 0x00054),
    ("OFF_RAM_WINDOW_LG2", 0x00058),
    ("OFF_MON_SENSE", 0x0005C),
    ("OFF_HALT_EXC_MASK0", 0x00060),
    ("OFF_HALT_EXC_MASK1", 0x00064),
    ("OFF_HALT_EXC_MASK2", 0x00068),
    ("OFF_HALT_EXC_MASK3", 0x0006C),
    ("OFF_HALT_EXC_MASK4", 0x00070),
    ("OFF_HALT_EXC_MASK5", 0x00074),
    ("OFF_HALT_EXC_MASK6", 0x00078),
    ("OFF_HALT_EXC_MASK7", 0x0007C),
    ("OFF_BP_SKIP_ONCE", 0x00080),
    ("OFF_BREAK_PC1", 0x00084),
    ("OFF_BREAK_PC2", 0x00088),
    ("OFF_BREAK_PC3", 0x0008C),
    ("OFF_BREAK_PC_CTRL", 0x00090),
    ("OFF_DBL_FAULT_PC", 0x00094),
    ("OFF_DBL_FAULT_VEC", 0x00098),
    ("OFF_PC_MISALIGNED_PC", 0x0009C),
    ("OFF_FEATURES", 0x000A0),
    ("OFF_DBG_RESET_CTL", 0x000A4),
    ("OFF_CAP_TRACE", 0x000A8),
    ("OFF_CAP_TRACE2", 0x000AC),
    ("OFF_WP0_ADDR", 0x000B0),
    ("OFF_WP0_AMASK", 0x000B4),
    ("OFF_WP0_VALUE", 0x000B8),
    ("OFF_WP0_CTRL", 0x000BC),
    ("OFF_WP1_ADDR", 0x000C0),
    ("OFF_WP1_AMASK", 0x000C4),
    ("OFF_WP1_VALUE", 0x000C8),
    ("OFF_WP1_CTRL", 0x000CC),
    ("OFF_WP_HIT", 0x000D0),
    ("OFF_WP_HIT_ADDR", 0x000D4),
    ("OFF_WP_HIT_DATA", 0x000D8),
    ("OFF_WP_HIT_PC", 0x000DC),
    ("OFF_AT0_CTRL", 0x000E0),
    ("OFF_AT0_MATCH", 0x000E4),
    ("OFF_AT0_D0VAL", 0x000E8),
    ("OFF_AT1_CTRL", 0x000EC),
    ("OFF_AT1_MATCH", 0x000F0),
    ("OFF_AT1_D0VAL", 0x000F4),
    ("OFF_AT_SKIP_ONCE", 0x000F8),
    ("OFF_AT_HIT", 0x000FC),
    ("OFF_AT_HIT_PC", 0x00100),
    ("OFF_AT_HIT_A0", 0x00104),
    ("OFF_AT_HIT_D0", 0x00108),
    ("OFF_DCACHE_PROBE_SEL", 0x00200),
    ("OFF_DCACHE_PROBE_TAG", 0x00204),
    ("OFF_DCACHE_PROBE_FLAGS", 0x00208),
    ("OFF_DCACHE_PROBE_DATA", 0x0020C),
    ("OFF_DCACHE_OP", 0x00210),
    ("OFF_ICACHE_OP", 0x00214),
    ("OFF_CYCLE_LO", 0x01000),
    ("OFF_CYCLE_HI", 0x01004),
    ("OFF_INST_LO", 0x01008),
    ("OFF_INST_HI", 0x0100C),
    ("OFF_MISPRED_COUNT", 0x01010),
    ("OFF_FLUSH_COUNT", 0x01014),
    ("OFF_EXC_COUNT", 0x01018),
    ("OFF_STALL_DC", 0x0101C),
    ("OFF_STALL_GRANT", 0x01020),
    ("OFF_STALL_EXC", 0x01024),
    ("OFF_STALL_WALK", 0x01028),
    ("OFF_ARCH_D0", 0x02000),
    ("OFF_ARCH_D1", 0x02004),
    ("OFF_ARCH_D2", 0x02008),
    ("OFF_ARCH_D3", 0x0200C),
    ("OFF_ARCH_D4", 0x02010),
    ("OFF_ARCH_D5", 0x02014),
    ("OFF_ARCH_D6", 0x02018),
    ("OFF_ARCH_D7", 0x0201C),
    ("OFF_ARCH_A0", 0x02020),
    ("OFF_ARCH_A1", 0x02024),
    ("OFF_ARCH_A2", 0x02028),
    ("OFF_ARCH_A3", 0x0202C),
    ("OFF_ARCH_A4", 0x02030),
    ("OFF_ARCH_A5", 0x02034),
    ("OFF_ARCH_A6", 0x02038),
    ("OFF_ARCH_A7", 0x0203C),
    ("OFF_ARCH_USP", 0x02040),
    ("OFF_ARCH_SSP", 0x02044),
    ("OFF_ARCH_ISP", 0x02048),
    ("OFF_ARCH_SR", 0x0204C),
    ("OFF_ARCH_VBR", 0x02050),
    ("OFF_ARCH_CACR", 0x02054),
    ("OFF_ARCH_TC", 0x02058),
    ("OFF_ARCH_ITT0", 0x0205C),
    ("OFF_ARCH_ITT1", 0x02060),
    ("OFF_ARCH_DTT0", 0x02064),
    ("OFF_ARCH_DTT1", 0x02068),
    ("OFF_ARCH_URP", 0x0206C),
    ("OFF_ARCH_SRP", 0x02070),
    ("OFF_ARCH_PC", 0x02074),
    ("OFF_ARCH_APPLY", 0x02078),
    ("OFF_ARCH_STATUS", 0x0207C),
    ("OFF_ARCH_SFC", 0x02080),
    ("OFF_ARCH_DFC", 0x02084),
    ("OFF_LIVE_VBR", 0x02100),
    ("OFF_LIVE_SR", 0x02104),
    ("OFF_LIVE_A7", 0x02108),
    ("OFF_LIVE_USP", 0x0210C),
    ("OFF_LIVE_DREG0", 0x02110),
    ("OFF_LIVE_DREG1", 0x02114),
    ("OFF_LIVE_DREG2", 0x02118),
    ("OFF_LIVE_DREG3", 0x0211C),
    ("OFF_LIVE_DREG4", 0x02120),
    ("OFF_LIVE_DREG5", 0x02124),
    ("OFF_LIVE_DREG6", 0x02128),
    ("OFF_LIVE_DREG7", 0x0212C),
    ("OFF_LIVE_AREG0", 0x02130),
    ("OFF_LIVE_AREG1", 0x02134),
    ("OFF_LIVE_AREG2", 0x02138),
    ("OFF_LIVE_AREG3", 0x0213C),
    ("OFF_LIVE_AREG4", 0x02140),
    ("OFF_LIVE_AREG5", 0x02144),
    ("OFF_LIVE_AREG6", 0x02148),
    ("OFF_LIVE_AREG7", 0x0214C),
    ("OFF_LIVE_MMU_TC", 0x02160),
    ("OFF_LIVE_MMU_DTT0", 0x02164),
    ("OFF_LIVE_MMU_DTT1", 0x02168),
    ("OFF_LIVE_MMU_ITT0", 0x0216C),
    ("OFF_LIVE_MMU_ITT1", 0x02170),
    ("OFF_LIVE_MMU_SRP", 0x02174),
    ("OFF_LIVE_MMU_URP", 0x02178),
    ("OFF_LIVE_SSP", 0x0217C),
    ("OFF_LIVE_ISP", 0x02180),
    ("OFF_LIVE_CACR", 0x02184),
    ("OFF_LIVE_SFC", 0x02188),
    ("OFF_LIVE_DFC", 0x0218C),
    ("OFF_LIVE_PC", 0x02190),
    ("OFF_LIVE_MMUSR", 0x02194),
    ("OFF_WEDGE0", 0x03000),
    ("OFF_WEDGE1", 0x03004),
    ("OFF_WEDGE2", 0x03008),
    ("OFF_WEDGE3", 0x0300C),
    ("OFF_FAULT_SNAP_VALID", 0x03010),
    ("OFF_FAULT_SNAP_W0", 0x03014),
    ("OFF_FAULT_SNAP_W1", 0x03018),
    ("OFF_FAULT_SNAP_W2", 0x0301C),
    ("OFF_FAULT_SNAP_W3", 0x03020),
    ("OFF_FAULT_SNAP_CLEAR", 0x03024),
    ("OFF_RTS_SNAP_VALID", 0x03028),
    ("OFF_RTS_SNAP_W0", 0x0302C),
    ("OFF_RTS_SNAP_W1", 0x03030),
    ("OFF_RTS_SNAP_W2", 0x03034),
    ("OFF_RTS_SNAP_W3", 0x03038),
    ("OFF_RTS_SNAP_W4", 0x0303C),
    ("OFF_RTS_SNAP_CLEAR", 0x03040),
    ("OFF_PC_TRACE_BODY", 0x10000),
    ("OFF_PC_TRACE_HEAD", 0x11000),
    ("OFF_EXC_RING_BODY", 0x12000),
    ("OFF_EXC_RING_HEAD", 0x13000),
    ("OFF_BRANCH_RING_BODY", 0x14000),
    ("OFF_BRANCH_RING_HEAD", 0x15000),
  )

  /** Capability bitmap: (name, bit, stage-that-makes-it-real). */
  val features: Seq[(String, Int, Int)] = Seq(
    ("dbg_reset_domain", 0, 1),
    ("axi_ready_gated", 1, 1),
    ("cfg_wipe", 2, 1),
    ("cpu_reset_count", 3, 1),
    ("pc_trace", 4, 3),
    ("exc_ring", 5, 3),
    ("break_pc_multi", 6, 5),
    ("halt_exc_mask", 7, 5),
    ("fault_snap", 8, 9),
    ("rts_snap", 9, 9),
    ("live_arch", 10, 3),
    ("dcache_probe", 11, 4),
    ("perf_counters", 12, 7),
    ("watchpoints", 13, 6),
    ("trace_trigger", 14, 9),
    ("atrap_bp", 15, 6),
    ("atrap_regcap", 16, 6),
    ("atrap_d0qual", 17, 6),
    ("mon_sense", 18, 1),
    ("arch_apply_stays_halted", 19, 3),
    ("arch_dirty_apply", 20, 3),
    ("cache_maint_only", 21, 4),
    ("macro_retire_count", 22, 2),
    ("stop_status_v2", 23, 2),
    ("branch_ring", 24, 3),
  )

  /** OR of (1 << bit) for every feature this stage genuinely implements.
    * This IS the spec section 3.4 honesty rule in executable form: a build
    * cannot advertise a bit whose behaviour it has not built. */
  def featuresForStage(stage: Int): BigInt =
    features.filter(_._3 <= stage)
            .foldLeft(BigInt(0))((acc, f) => acc | (BigInt(1) << f._2))

  /** Socket ports, verbatim from macqd700-soc/rtl/soc/cpu_socket.vh:
    * (name, direction as seen from the CPU, width). */
  val ports: Seq[(String, String, Int)] = Seq(
    ("dbg_axi_awaddr", "IN", 20),
    ("dbg_axi_awvalid", "IN", 1),
    ("dbg_axi_awready", "OUT", 1),
    ("dbg_axi_wdata", "IN", 32),
    ("dbg_axi_wstrb", "IN", 4),
    ("dbg_axi_wvalid", "IN", 1),
    ("dbg_axi_wready", "OUT", 1),
    ("dbg_axi_bresp", "OUT", 2),
    ("dbg_axi_bvalid", "OUT", 1),
    ("dbg_axi_bready", "IN", 1),
    ("dbg_axi_araddr", "IN", 20),
    ("dbg_axi_arvalid", "IN", 1),
    ("dbg_axi_arready", "OUT", 1),
    ("dbg_axi_rdata", "OUT", 32),
    ("dbg_axi_rresp", "OUT", 2),
    ("dbg_axi_rvalid", "OUT", 1),
    ("dbg_axi_rready", "IN", 1),
    ("cpu_cold_reset_pulse", "OUT", 1),
    ("cpu_cold_reset_hold", "OUT", 1),
    ("cpu_ram_window_lg2", "OUT", 6),
    ("cpu_mon_sense", "OUT", 7),
    ("init_done_seen", "IN", 1),
  )
}
