# Debug/Control AXI Slave — Stage 0 + Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the frozen `dbg_axi` register/capability contract as one machine-readable source of truth with generated Scala/Tcl/Python constants and host-side protocol tests (Stage 0), then implement `DebugCtrlPlugin` — the CPU-side AXI4-Lite debug/control slave with its own surviving reset domain, identity/capability CSRs, CONTROL/STATUS, CPU-reset counting, and the SoC-fabric control outputs (Stage 1).

**Architecture:** A new `m68k040.debug` package adds a `FiberPlugin` (`DebugCtrlPlugin`) that declares the `dbg_axi_*` AXI4-Lite slave and the `cpu_cold_reset_*` / `cpu_ram_window_lg2` / `cpu_mon_sense` / `init_done_seen` socket group directly as core-level IO, exactly as `DcachePlugin` declares its `Axi4` master. All debug registers live in a *derived* SpinalHDL `ClockDomain` whose reset comes from a deliberately reset-less power-on counter (SpinalHDL `resetKind = BOOT`), so host configuration survives every CPU reset while the CPU reset is only *observed* as an edge. A single flat text file (`tools/debug/debug_regmap.def`) is the sole definition of every offset, feature bit, constant, and socket port name; Scala, Tcl and Python constant files are generated from it and cross-checked against the deployed sibling contract.

**Tech Stack:** SpinalHDL 1.14.1 (`spinalhdl-core`, `spinalhdl-lib`, `spinalhdl-sim`), Scala 2.13.16, ScalaTest 3.2.19, SpinalSim/Verilator, Python 3 (standard library only — no pytest in this repo), Tcl (consumed by the sibling `macqd700-soc/tools/jtag_repl.tcl`), Vivado for the post-route gate.

## Global Constraints

- Branch is `fmax-closure-fanout`; the plan's parent commit is whatever `git rev-parse HEAD` reports at execution start. Never `git checkout <sha>` in the shared tree — use `git worktree add` for any isolated before/after verification (project standing rule).
- **No `Global.scala` key may be added for debug.** Spec §0.9: "Add no `Global` database key for debug. Parameters are constructor/config values." `src/main/scala/m68k040/Global.scala` currently holds exactly 13 keys (`ROB_DEPTH` … `PHT_ENTRIES`) and must be byte-identical at the end of this plan.
- **Socket parameters are fixed by `/home/qwertyoruiop/macqd700-soc/rtl/soc/cpu_socket.vh:85-86`:** `` `define CPU_SOCKET_DBG_AW 20 `` and `` `define CPU_SOCKET_DBG_DW 32 ``.
- **The `dbg_axi` port list is fixed by `cpu_socket.vh:145-162`** and contains NO `AWPROT`/`ARPROT`: `dbg_axi_awaddr`, `dbg_axi_awvalid`, `dbg_axi_awready`, `dbg_axi_wdata`, `dbg_axi_wstrb`, `dbg_axi_wvalid`, `dbg_axi_wready`, `dbg_axi_bresp`, `dbg_axi_bvalid`, `dbg_axi_bready`, `dbg_axi_araddr`, `dbg_axi_arvalid`, `dbg_axi_arready`, `dbg_axi_rdata`, `dbg_axi_rresp`, `dbg_axi_rvalid`, `dbg_axi_rready`.
- **The SoC-fabric control group is fixed by `cpu_socket.vh:170-176`:** `output cpu_cold_reset_pulse`, `output cpu_cold_reset_hold`, `output [5:0] cpu_ram_window_lg2`, `output [6:0] cpu_mon_sense`, `input init_done_seen`.
- **Version epoch is `0xDEB6_0100`** (spec §3.1). `DBG_BUILD_ID` remains supplied by the SoC build (spec §3.1) and is therefore a plugin constructor parameter, not a hardcoded literal.
- **Frozen offsets are append-only and may never be repurposed** (spec §3.2): "The first implementation need not decode all of these as functional registers. It must reserve them, return zero for absent functions, and never repurpose them."
- **Feature bits 0-18 retain their deployed meanings and are never renumbered** (spec §3.4); reserved new bits are 19 `arch_apply_stays_halted`, 20 `arch_dirty_apply`, 21 `cache_maint_only`, 22 `macro_retire_count`, 23 `stop_status_v2`.
- **Feature honesty rule (spec §3.4, verbatim):** "No feature bit may advertise a tied-off counter, stale shadow, placeholder probe, or operation that can be silently dropped."
- **AXI behaviour rules (spec §3.1, verbatim):** "capture AW and W independently and respond only after both have arrived"; "honor `WSTRB` per byte for ordinary RW fields"; "return OKAY/zero for unmapped reads and OKAY/drop for unmapped writes"; "keep READY low during debug-domain reset"; "never clear an accepted request merely because CPU reset asserted"; "pipeline the address/bank decode and read mux as needed. No host command relies on a fixed two-cycle legacy latency"; "hold `RVALID`/`BVALID` and payload stable until accepted."
- **Reset-domain rule (spec §10.1, verbatim):** "The AXI slave and cold-reset-hold register must never be reset by the CPU reset they request. A synchronized CPU-reset edge increments the surviving reset counter and clears runtime state without disturbing accepted AXI handshakes or host configuration."
- **Stage-1 applicable assertions (spec §13, verbatim):** "CPU reset cannot change surviving debug configuration"; "debug reset cannot accept AXI requests"; "feature bits imply reachable, non-tied-off behavior."
- **Stage-1 acceptance gate (spec §11, verbatim):** "basic CSR + stop/step + arch/cache tranche: no more than +1.0% device LUT and FF, no DSP/BRAM except explicitly enabled trace, and achieved FMax no worse than 2% from the uncontended reference while remaining above the architecture's hard floor"; and "no top timing endpoint may be a debug comparator, CSR read mux, or high-fanout debug enable. If one appears, add a pipeline/register stage before accepting the slice." These are gates, not estimates — "Report absolute utilization and deltas for every implemented tranche."
- **The FMax reference is re-read at execution time, never hardcoded.** The most recent recorded post-route number on this branch is **189.502 MHz** (commit `c125d96`, `.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md:357-374`, with CLB LUTs 123602 / CLB Registers 56029 at `866437c`), but task #219's live FMax-closure campaign may supersede it. Task 13 re-reads the current reference before judging its own result.
- **Never run two Vivado sessions concurrently** and never launch a gate while a JTAG session is live (spec §12 "Gates for every RTL stage" item 5). FMax on this machine is unreliable under contention.
- **Python is standard-library only.** `python3` exists at `/usr/bin/python3`; `pytest` is NOT installed and there are zero `.py` files in this repository today. Every Python file this plan adds is a self-contained script runnable as `python3 <file>` that exits non-zero on failure.
- **Reset kind of the core clock domain is ASYNC/active-HIGH**, ports `clk` and `reset` (confirmed in `generated/M68kFullCoreSynth.v:183-184` and `always @(posedge clk or posedge reset)`).
- POR defaults copied verbatim from the deployed controller: `ram_window_lg2` reset **26** (`macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:1805`), write clamp **[22, 30]** (`:2202-2206`), `mon_sense` reset **7'h06** (`:1814`).

---

## Task 1: Machine-readable register/capability definition + parser

**Files:**
- Create: `tools/debug/debug_regmap.def`
- Create: `tools/debug/regmap.py`
- Test: `tools/debug/test_debug_regmap.py`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `tools/debug/debug_regmap.def` — the single source of truth. Line-oriented, `#` comments, whitespace-separated columns, record kinds `REG`, `BLK`, `RANGE`, `FEAT`, `CONST`, `PORT`.
  - `tools/debug/regmap.py` exporting `def load(path: str = None) -> RegMap`, where `RegMap` is a `class` with attributes `regs: list[Reg]`, `blks: list[Blk]`, `ranges: list[Range]`, `feats: list[Feat]`, `consts: dict[str, int]`, `ports: list[Port]`, and methods `offsets() -> dict[str, int]` (every REG plus every expanded BLK element plus every RANGE base) and `features_for_stage(stage: int) -> int`.
  - Record tuples: `Reg(name, offset, access, stage, reset, desc)`, `Blk(name, base, count, stride, access, stage, desc)`, `Range(name, base, size, access, stage, desc)`, `Feat(name, bit, stage, desc)`, `Port(name, direction, width, desc)`.
  - `STAGE` semantics: integer 0-7 = the implementation stage that makes the item real; `9` = reserved namespace that is never implemented (retired or diagnostic-only).

- [ ] **Step 1: Write the failing test**

Create `tools/debug/test_debug_regmap.py`:

```python
#!/usr/bin/env python3
"""Self-check for tools/debug/debug_regmap.def.

Standard library only -- this repository has no pytest. Run:
    python3 tools/debug/test_debug_regmap.py
Exits 0 on success, 1 on the first failure (with a diagnostic on stderr).
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import regmap  # noqa: E402

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label + (("  -- " + detail) if detail else ""))
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def main():
    rm = regmap.load()

    check("def file parses and is non-trivial",
          len(rm.regs) >= 80, "only %d REG records" % len(rm.regs))

    offs = rm.offsets()

    # Every offset is 4-byte aligned and inside the 20-bit BAR-local window.
    bad_align = sorted(n for n, o in offs.items() if o % 4 != 0)
    check("every offset is 32-bit aligned", not bad_align, repr(bad_align))
    bad_range = sorted(n for n, o in offs.items() if o < 0 or o >= (1 << 20))
    check("every offset fits the 20-bit dbg_axi window", not bad_range, repr(bad_range))

    # No two names may claim the same offset -- the deployed controller once had a
    # silent duplicate-case collision at 0x00054 (debug_ctrl.v:460-466).
    seen = {}
    dupes = []
    for name, off in sorted(offs.items(), key=lambda kv: (kv[1], kv[0])):
        if off in seen:
            dupes.append("0x%05X: %s vs %s" % (off, seen[off], name))
        else:
            seen[off] = name
    check("no two register names share an offset", not dupes, "; ".join(dupes))

    # Feature bits: dense 0..N, never renumbered, unique.
    bits = sorted(f.bit for f in rm.feats)
    check("feature bits are unique", len(bits) == len(set(bits)), repr(bits))
    check("feature bits are dense from 0", bits == list(range(len(bits))), repr(bits))
    check("feature bits fit a 32-bit register", all(0 <= b < 32 for b in bits), repr(bits))

    # The five deployed names at their frozen bit numbers (spec section 3.4).
    frozen = {
        "dbg_reset_domain": 0, "axi_ready_gated": 1, "cfg_wipe": 2,
        "cpu_reset_count": 3, "pc_trace": 4, "exc_ring": 5,
        "break_pc_multi": 6, "halt_exc_mask": 7, "fault_snap": 8,
        "rts_snap": 9, "live_arch": 10, "dcache_probe": 11,
        "perf_counters": 12, "watchpoints": 13, "trace_trigger": 14,
        "atrap_bp": 15, "atrap_regcap": 16, "atrap_d0qual": 17,
        "mon_sense": 18, "arch_apply_stays_halted": 19,
        "arch_dirty_apply": 20, "cache_maint_only": 21,
        "macro_retire_count": 22, "stop_status_v2": 23,
    }
    by_name = {f.name: f.bit for f in rm.feats}
    check("frozen feature bit numbering is exact", by_name == frozen,
          "got %r" % (sorted(by_name.items()),))

    # Stage 1 advertises exactly the five bits whose behaviour Stage 1 makes real.
    check("features_for_stage(1) == 0x0004000F",
          rm.features_for_stage(1) == 0x0004000F,
          "got 0x%08X" % rm.features_for_stage(1))
    check("features_for_stage(0) == 0", rm.features_for_stage(0) == 0)

    # Constants required by the RTL and by every host tool.
    for name, want in (("VERSION_VALUE", 0xDEB60100), ("DBG_AW", 20), ("DBG_DW", 32),
                       ("RAM_WINDOW_LG2_POR", 26), ("RAM_WINDOW_LG2_MIN", 22),
                       ("RAM_WINDOW_LG2_MAX", 30), ("MON_SENSE_POR", 0x06)):
        check("CONST %s == %d" % (name, want), rm.consts.get(name) == want,
              "got %r" % (rm.consts.get(name),))

    # Access strings are from the closed set.
    ok_access = {"RO", "RW", "W1P", "RAZWI", "MIXED"}
    bad_acc = sorted({r.access for r in rm.regs} - ok_access)
    check("REG access column uses only the closed set", not bad_acc, repr(bad_acc))

    # Ports: 17 dbg_axi signals + 5 SoC-fabric control signals.
    pnames = sorted(p.name for p in rm.ports)
    want_ports = sorted([
        "dbg_axi_awaddr", "dbg_axi_awvalid", "dbg_axi_awready",
        "dbg_axi_wdata", "dbg_axi_wstrb", "dbg_axi_wvalid", "dbg_axi_wready",
        "dbg_axi_bresp", "dbg_axi_bvalid", "dbg_axi_bready",
        "dbg_axi_araddr", "dbg_axi_arvalid", "dbg_axi_arready",
        "dbg_axi_rdata", "dbg_axi_rresp", "dbg_axi_rvalid", "dbg_axi_rready",
        "cpu_cold_reset_pulse", "cpu_cold_reset_hold", "cpu_ram_window_lg2",
        "cpu_mon_sense", "init_done_seen",
    ])
    check("PORT records match the cpu_socket.vh surface", pnames == want_ports,
          "missing=%r extra=%r" % (sorted(set(want_ports) - set(pnames)),
                                   sorted(set(pnames) - set(want_ports))))

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && python3 tools/debug/test_debug_regmap.py
```

Expected output: `ModuleNotFoundError: No module named 'regmap'` (exit code 1).

- [ ] **Step 3a: Write the definition file**

Create `tools/debug/debug_regmap.def`:

```text
# tools/debug/debug_regmap.def -- SINGLE SOURCE OF TRUTH for the CPU-side
# dbg_axi debug/control register map, capability bitmap, well-known constants,
# and the SoC socket port names.
#
# Authority chain (do not edit this file from memory -- re-read these):
#   * docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md
#     sections 3.2 (frozen offsets), 3.3 (CONTROL/STATUS), 3.4 (feature bits).
#   * macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:438-719 (the DEPLOYED
#     localparam offsets -- these win over any prose).
#   * macqd700-soc/rtl/soc/cpu_socket.vh:85-86,145-176 (socket parameters,
#     dbg_axi port list, SoC-fabric control group).
#
# FROZEN-OFFSET DISCIPLINE: an offset listed here is reserved forever. An
# unimplemented offset returns zero and drops writes with an OKAY response; it
# is NEVER repurposed. Feature bits are append-only and never renumbered.
#
# Columns are whitespace-separated. '#' starts a comment. Record kinds:
#   REG   <NAME> <OFFSET> <ACCESS> <STAGE> <RESET> <DESC...>
#   BLK   <NAME> <BASE> <COUNT> <STRIDE> <ACCESS> <STAGE> <DESC...>
#   RANGE <NAME> <BASE> <SIZE> <ACCESS> <STAGE> <DESC...>
#   FEAT  <NAME> <BIT> <STAGE> <DESC...>
#   CONST <NAME> <VALUE> <DESC...>
#   PORT  <NAME> <DIR> <WIDTH> <DESC...>
#
# ACCESS is one of RO RW W1P RAZWI MIXED.
# STAGE is the implementation stage that makes the item REAL (0-7), or 9 for a
# reserved-forever namespace slot that no stage implements.
# RESET is the power-on read value, or '-' when the value is not a constant.

# ---------------------------------------------------------------- constants --
CONST VERSION_VALUE       0xDEB60100 Version epoch for this core (spec 3.1). Informational; behaviour is discovered via OFF_FEATURES.
CONST DBG_AW              20         cpu_socket.vh CPU_SOCKET_DBG_AW.
CONST DBG_DW              32         cpu_socket.vh CPU_SOCKET_DBG_DW.
CONST RAM_WINDOW_LG2_POR  26         debug_ctrl.v:1805 reset value (64 MiB).
CONST RAM_WINDOW_LG2_MIN  22         debug_ctrl.v:2202 write clamp lower bound.
CONST RAM_WINDOW_LG2_MAX  30         debug_ctrl.v:2204 write clamp upper bound.
CONST MON_SENSE_POR       0x06       debug_ctrl.v:1814 reset value; matches cpu_socket.vh POR default 7'h06.
CONST POR_CYCLES_DEFAULT  16         Debug power-on reset length in core clocks; debug_reset_ctl.v POR_CYCLES default.

# ------------------------------------------------- identity / control block --
REG OFF_VERSION           0x00000 RO    1 0xDEB60100 Version epoch.
REG OFF_BUILD_ID          0x00004 RO    1 -          SoC-supplied build ID (plugin constructor parameter).
REG OFF_CONTROL           0x00008 MIXED 1 0x00000000 Control level/pulse bits; see spec 3.3.
REG OFF_STATUS            0x0000C RO    1 -          halted/exc/init-done/running/auto-halt.
REG OFF_PC                0x00010 RO    2 0x00000000 Next live PC.
REG OFF_LAST_PC           0x00014 RO    2 0x00000000 Last committed PC.
REG OFF_REDIRECT_PC       0x00018 RW    9 0x00000000 Legacy host-forced redirect PC; reserved, not implemented.
REG OFF_REDIRECT_TRIGGER  0x0001C RW    9 0x00000000 Legacy redirect trigger; reserved, not implemented.
REG OFF_IRQ_INJECT        0x00020 RW    9 0x00000000 Legacy IRQ injection level; reserved, not implemented.
REG OFF_EXC_VEC           0x00024 RO    5 0x00000000 Last exception vector.
REG OFF_EXC_PC            0x00028 RO    5 0x00000000 Last exception PC.
REG OFF_RESET_CAUSE       0x0002C RO    9 0x00000000 Legacy reset cause; reserved, not implemented.
REG OFF_HALT_AFTER_LO     0x00030 RW    2 0x00000000 Absolute macro-count halt-after target, low word.
REG OFF_HALT_AFTER_HI     0x00034 RW    2 0x00000000 Absolute macro-count halt-after target, high word.
REG OFF_BREAK_PC0         0x00038 RW    5 0x00000000 PC breakpoint slot 0.
REG OFF_HALT_CTL          0x0003C MIXED 2 0x00000000 Halt control; bit 2 clears sticky reason/report latches.
REG OFF_HALT_REASON       0x00040 RO    2 0x00000000 Sticky halt reason bits.
REG OFF_HALT_HIT_PC       0x00044 RO    5 0x00000000 PC of the macro that caused the stop.
REG OFF_HALT_HIT_INST_LO  0x00048 RO    2 0x00000000 Macro count at the stop, low word.
REG OFF_HALT_HIT_INST_HI  0x0004C RO    2 0x00000000 Macro count at the stop, high word.
REG OFF_HALT_EXC_VEC      0x00050 RW    9 0x00000000 Deployed dead register (RW, comparator ignores it); reserved.
REG OFF_EXC_FAULT_ADDR    0x00054 RO    5 0x00000000 Faulting address for the last exception.
REG OFF_RAM_WINDOW_LG2    0x00058 RW    1 0x0000001A DDR RAM-window size select; clamped to [22,30], POR 26.
REG OFF_MON_SENSE         0x0005C RW    1 0x00000006 DAFB monitor-sense code, bits [6:0]; bit 6 = extended-monitor flag.
BLK OFF_HALT_EXC_MASK     0x00060 8 0x04 RW 5 256-bit halt-on-exception mask, 8 lanes of 32 bits.
REG OFF_BP_SKIP_ONCE      0x00080 RW    5 0x00000000 Per-slot breakpoint skip-once latches.
REG OFF_BREAK_PC1         0x00084 RW    5 0x00000000 PC breakpoint slot 1.
REG OFF_BREAK_PC2         0x00088 RW    5 0x00000000 PC breakpoint slot 2.
REG OFF_BREAK_PC3         0x0008C RW    5 0x00000000 PC breakpoint slot 3.
REG OFF_BREAK_PC_CTRL     0x00090 MIXED 5 0x00000000 Breakpoint enables + hit descriptor.
REG OFF_DBL_FAULT_PC      0x00094 RO    5 0x00000000 Double-fault PC capture.
REG OFF_DBL_FAULT_VEC     0x00098 RO    5 0x00000000 Double-fault vector capture.
REG OFF_PC_MISALIGNED_PC  0x0009C RO    9 0x00000000 Retired legacy diagnostic; reserved, always reads 0.

# -------------------------------------------- capability / discovery block --
REG OFF_FEATURES          0x000A0 RO    1 0x0004000F Capability bitmap; see FEAT records.
REG OFF_DBG_RESET_CTL     0x000A4 MIXED 1 0x00000000 Read [31:16] = CPU-reset count; write bit0 = cfg wipe, bit1 = count clear.
REG OFF_CAP_TRACE         0x000A8 RO    7 0x00000000 [31:16] PC-trace depth, [15:0] exception-ring depth.

# ------------------------------------------------ watchpoints and A-traps --
REG OFF_WP0_ADDR          0x000B0 RW    6 0x00000000 Watchpoint 0 physical address.
REG OFF_WP0_AMASK         0x000B4 RW    6 0x00000000 Watchpoint 0 address mask.
REG OFF_WP0_VALUE         0x000B8 RW    6 0x00000000 Watchpoint 0 value compare.
REG OFF_WP0_CTRL          0x000BC RW    6 0x00000000 Watchpoint 0 control.
REG OFF_WP1_ADDR          0x000C0 RW    6 0x00000000 Watchpoint 1 physical address.
REG OFF_WP1_AMASK         0x000C4 RW    6 0x00000000 Watchpoint 1 address mask.
REG OFF_WP1_VALUE         0x000C8 RW    6 0x00000000 Watchpoint 1 value compare.
REG OFF_WP1_CTRL          0x000CC RW    6 0x00000000 Watchpoint 1 control.
REG OFF_WP_HIT            0x000D0 MIXED 6 0x00000000 Watchpoint hit report head (depth-2 queue).
REG OFF_WP_HIT_ADDR       0x000D4 RO    6 0x00000000 Watchpoint hit address.
REG OFF_WP_HIT_DATA       0x000D8 RO    6 0x00000000 Watchpoint hit data.
REG OFF_WP_HIT_PC         0x000DC RO    6 0x00000000 Watchpoint hit PC.
REG OFF_AT0_CTRL          0x000E0 RW    6 0x00000000 A-trap slot 0 control.
REG OFF_AT0_MATCH         0x000E4 RW    6 0x00000000 A-trap slot 0 opcode/care-mask.
REG OFF_AT0_D0VAL         0x000E8 RW    6 0x00000000 A-trap slot 0 D0 qualifier.
REG OFF_AT1_CTRL          0x000EC RW    6 0x00000000 A-trap slot 1 control.
REG OFF_AT1_MATCH         0x000F0 RW    6 0x00000000 A-trap slot 1 opcode/care-mask.
REG OFF_AT1_D0VAL         0x000F4 RW    6 0x00000000 A-trap slot 1 D0 qualifier.
REG OFF_AT_SKIP_ONCE      0x000F8 RW    6 0x00000000 A-trap skip-once latches.
REG OFF_AT_HIT            0x000FC MIXED 6 0x00000000 A-trap hit descriptor.
REG OFF_AT_HIT_PC         0x00100 RO    6 0x00000000 A-trap hit PC.
REG OFF_AT_HIT_A0         0x00104 RO    6 0x00000000 A-trap hit A0 capture.
REG OFF_AT_HIT_D0         0x00108 RO    6 0x00000000 A-trap hit D0 capture.

# --------------------------------------------- cache probe and operations --
REG OFF_DCACHE_PROBE_SEL   0x00200 RW    4 0x00000000 D-cache probe way/set select.
REG OFF_DCACHE_PROBE_TAG   0x00204 RO    4 0x00000000 D-cache probe tag readback.
REG OFF_DCACHE_PROBE_FLAGS 0x00208 RO    4 0x00000000 D-cache probe valid/dirty flags.
REG OFF_DCACHE_PROBE_DATA  0x0020C RO    4 0x00000000 D-cache probe data word.
REG OFF_DCACHE_OP          0x00210 MIXED 4 0x00000000 Write bit0 START, bit1 push-vs-invalidate; read bit0 BUSY, bit1 DONE, bits[3:2] cache select.
REG OFF_ICACHE_OP          0x00214 MIXED 4 0x00000000 I-cache invalidate command/status; shares status with OFF_DCACHE_OP.

# ------------------------------------------------------------- telemetry --
REG OFF_CYCLE_LO          0x01000 RO    7 0x00000000 Free-running cycle count, low word.
REG OFF_CYCLE_HI          0x01004 RO    7 0x00000000 Free-running cycle count, high word.
REG OFF_INST_LO           0x01008 RO    2 0x00000000 Architectural macro-instruction count, low word.
REG OFF_INST_HI           0x0100C RO    2 0x00000000 Architectural macro-instruction count, high word.
REG OFF_MISPRED_COUNT     0x01010 RO    7 0x00000000 Branch mispredictions; zero until a real producer exists.
REG OFF_FLUSH_COUNT       0x01014 RO    7 0x00000000 Pipeline flushes; zero until a real producer exists.
REG OFF_EXC_COUNT         0x01018 RO    7 0x00000000 Exceptions taken; zero until a real producer exists.

# ------------------------------------------- architectural write shadows --
BLK OFF_ARCH_D            0x02000 8 0x04 RW 3 Architectural shadow D0-D7.
BLK OFF_ARCH_A            0x02020 8 0x04 RW 3 Architectural shadow A0-A7.
REG OFF_ARCH_USP          0x02040 RW    3 0x00000000 Architectural shadow USP.
REG OFF_ARCH_SSP          0x02044 RW    3 0x00000000 Architectural shadow SSP (compatibility view).
REG OFF_ARCH_ISP          0x02048 RW    3 0x00000000 Architectural shadow ISP.
REG OFF_ARCH_SR           0x0204C RW    3 0x00000000 Architectural shadow SR.
REG OFF_ARCH_VBR          0x02050 RW    3 0x00000000 Architectural shadow VBR.
REG OFF_ARCH_CACR         0x02054 RW    3 0x00000000 Architectural shadow CACR.
REG OFF_ARCH_TC           0x02058 RW    3 0x00000000 Architectural shadow TC.
REG OFF_ARCH_ITT0         0x0205C RW    3 0x00000000 Architectural shadow ITT0.
REG OFF_ARCH_ITT1         0x02060 RW    3 0x00000000 Architectural shadow ITT1.
REG OFF_ARCH_DTT0         0x02064 RW    3 0x00000000 Architectural shadow DTT0.
REG OFF_ARCH_DTT1         0x02068 RW    3 0x00000000 Architectural shadow DTT1.
REG OFF_ARCH_URP          0x0206C RW    3 0x00000000 Architectural shadow URP.
REG OFF_ARCH_SRP          0x02070 RW    3 0x00000000 Architectural shadow SRP.
REG OFF_ARCH_PC           0x02074 RW    3 0x00000000 Architectural shadow PC.
REG OFF_ARCH_APPLY        0x02078 W1P   3 0x00000000 Write starts the halted-only apply transaction.
REG OFF_ARCH_STATUS       0x0207C MIXED 3 0x00000000 bit0 BUSY, bit1 DONE, bit2 REJECTED.
REG OFF_ARCH_SFC          0x02080 RW    3 0x00000000 Architectural shadow SFC.
REG OFF_ARCH_DFC          0x02084 RW    3 0x00000000 Architectural shadow DFC.

# ----------------------------------------------------- live architectural --
REG OFF_LIVE_VBR          0x02100 RO    3 0x00000000 Live committed VBR.
REG OFF_LIVE_SR           0x02104 RO    3 0x00000000 Live committed SR.
REG OFF_LIVE_A7           0x02108 RO    3 0x00000000 Live committed active A7.
REG OFF_LIVE_USP          0x0210C RO    3 0x00000000 Live committed USP.
# NAMED "*REG" ON PURPOSE: the deployed map already uses OFF_LIVE_A7 for the live
# ACTIVE A7 at 0x02108 (debug_ctrl.v:676). A block named OFF_LIVE_A would expand to
# an OFF_LIVE_A7 at 0x0214C and silently collide with it.
BLK OFF_LIVE_DREG         0x02110 8 0x04 RO 3 Live committed D0-D7 (valid only at effective halt).
BLK OFF_LIVE_AREG         0x02130 8 0x04 RO 3 Live committed A0-A7 (valid only at effective halt).
REG OFF_LIVE_MMU_TC       0x02160 RO    3 0x00000000 Live committed TC.
REG OFF_LIVE_MMU_DTT0     0x02164 RO    3 0x00000000 Live committed DTT0.
REG OFF_LIVE_MMU_DTT1     0x02168 RO    3 0x00000000 Live committed DTT1.
REG OFF_LIVE_MMU_ITT0     0x0216C RO    3 0x00000000 Live committed ITT0.
REG OFF_LIVE_MMU_ITT1     0x02170 RO    3 0x00000000 Live committed ITT1.
REG OFF_LIVE_MMU_SRP      0x02174 RO    3 0x00000000 Live committed SRP.
REG OFF_LIVE_MMU_URP      0x02178 RO    3 0x00000000 Live committed URP.
REG OFF_LIVE_SSP          0x0217C RO    3 0x00000000 Live committed SSP.
REG OFF_LIVE_ISP          0x02180 RO    3 0x00000000 Live committed ISP.

# ------------------------------------- optional implementation diagnostics --
REG OFF_WEDGE0            0x03000 RO    9 0x00000000 Legacy wedge snapshot 0; reserved, not implemented.
REG OFF_WEDGE1            0x03004 RO    9 0x00000000 Legacy wedge snapshot 1; reserved, not implemented.
REG OFF_WEDGE2            0x03008 RO    9 0x00000000 Legacy wedge snapshot 2; reserved, not implemented.
REG OFF_WEDGE3            0x0300C RO    9 0x00000000 Legacy wedge snapshot 3; reserved, not implemented.
REG OFF_FAULT_SNAP_VALID  0x03010 RO    9 0x00000000 Legacy fault snapshot valid; reserved.
REG OFF_FAULT_SNAP_W0     0x03014 RO    9 0x00000000 Legacy fault snapshot word 0; reserved.
REG OFF_FAULT_SNAP_W1     0x03018 RO    9 0x00000000 Legacy fault snapshot word 1; reserved.
REG OFF_FAULT_SNAP_W2     0x0301C RO    9 0x00000000 Legacy fault snapshot word 2; reserved.
REG OFF_FAULT_SNAP_W3     0x03020 RO    9 0x00000000 Legacy fault snapshot word 3; reserved.
REG OFF_FAULT_SNAP_CLEAR  0x03024 W1P   9 0x00000000 Legacy fault snapshot clear; reserved.
REG OFF_RTS_SNAP_VALID    0x03028 RO    9 0x00000000 Legacy RTS snapshot valid; reserved.
REG OFF_RTS_SNAP_W0       0x0302C RO    9 0x00000000 Legacy RTS snapshot word 0; reserved.
REG OFF_RTS_SNAP_W1       0x03030 RO    9 0x00000000 Legacy RTS snapshot word 1; reserved.
REG OFF_RTS_SNAP_W2       0x03034 RO    9 0x00000000 Legacy RTS snapshot word 2; reserved.
REG OFF_RTS_SNAP_W3       0x03038 RO    9 0x00000000 Legacy RTS snapshot word 3; reserved.
REG OFF_RTS_SNAP_W4       0x0303C RO    9 0x00000000 Legacy RTS snapshot word 4; reserved.
REG OFF_RTS_SNAP_CLEAR    0x03040 W1P   9 0x00000000 Legacy RTS snapshot clear; reserved.

# ------------------------------------------------------ optional ring RAMs --
RANGE OFF_PC_TRACE_BODY   0x10000 0x1000 RO 7 PC trace ring body.
REG   OFF_PC_TRACE_HEAD   0x11000 RO 7 0x00000000 PC trace ring head pointer.
RANGE OFF_EXC_RING_BODY   0x12000 0x1000 RO 7 Exception ring body.
REG   OFF_EXC_RING_HEAD   0x13000 RO 7 0x00000000 Exception ring head pointer.

# ---------------------------------------------------------- feature bitmap --
FEAT dbg_reset_domain       0  1 Debug CSRs and configuration survive CPU reset.
FEAT axi_ready_gated        1  1 READY is low during debug reset; the slave never swallows a transaction.
FEAT cfg_wipe               2  1 OFF_DBG_RESET_CTL bit 0 restores host config to POR defaults.
FEAT cpu_reset_count        3  1 OFF_DBG_RESET_CTL[31:16] is a real observed-CPU-reset counter.
FEAT pc_trace               4  7 PC trace ring present.
FEAT exc_ring               5  7 Exception ring present.
FEAT break_pc_multi         6  5 Four precise PC breakpoint slots.
FEAT halt_exc_mask          7  5 256-bit halt-on-exception mask.
FEAT fault_snap             8  9 Legacy fault snapshot; never implemented on this core.
FEAT rts_snap               9  9 Legacy RTS snapshot; never implemented on this core.
FEAT live_arch             10  3 Live architectural register readback.
FEAT dcache_probe          11  4 D-cache probe readback AND cache operations both real.
FEAT perf_counters         12  7 Every advertised performance counter has a real producer.
FEAT watchpoints           13  6 Data watchpoints.
FEAT trace_trigger         14  9 Armed/filtered PC trace; not planned for this core.
FEAT atrap_bp              15  6 A-trap breakpoint slots.
FEAT atrap_regcap          16  6 AT_HIT_A0/D0 are real captures.
FEAT atrap_d0qual          17  6 Per-slot D0 qualifier.
FEAT mon_sense             18  1 OFF_MON_SENSE is a real register driving cpu_mon_sense.
FEAT arch_apply_stays_halted 19 3 Apply is atomic relative to execution and does not resume.
FEAT arch_dirty_apply      20  3 Only shadows written since the last apply/config-wipe are changed.
FEAT cache_maint_only      21  4 Halted-only push/invalidate works without legacy probe support.
FEAT macro_retire_count    22  2 OFF_INST_* and halt-after count macro-instructions, not uops.
FEAT stop_status_v2        23  2 Effective halt means commit recovery and stable architectural state.

# ------------------------------------------------------------ socket ports --
# Verbatim from macqd700-soc/rtl/soc/cpu_socket.vh:145-176. DIR is from the
# CPU's point of view. These names are forced onto the generated Verilog via
# SpinalHDL setName(), so the SoC wrapper binds them without a rename layer.
PORT dbg_axi_awaddr        IN  20 Write address, BAR-local.
PORT dbg_axi_awvalid       IN  1  Write address valid.
PORT dbg_axi_awready       OUT 1  Write address ready; LOW during debug reset.
PORT dbg_axi_wdata         IN  32 Write data.
PORT dbg_axi_wstrb         IN  4  Write byte strobes.
PORT dbg_axi_wvalid        IN  1  Write data valid.
PORT dbg_axi_wready        OUT 1  Write data ready; LOW during debug reset.
PORT dbg_axi_bresp         OUT 2  Write response; always OKAY.
PORT dbg_axi_bvalid        OUT 1  Write response valid.
PORT dbg_axi_bready        IN  1  Write response ready.
PORT dbg_axi_araddr        IN  20 Read address, BAR-local.
PORT dbg_axi_arvalid       IN  1  Read address valid.
PORT dbg_axi_arready       OUT 1  Read address ready; LOW during debug reset.
PORT dbg_axi_rdata         OUT 32 Read data; zero for unmapped offsets.
PORT dbg_axi_rresp         OUT 2  Read response; always OKAY.
PORT dbg_axi_rvalid        OUT 1  Read response valid.
PORT dbg_axi_rready        IN  1  Read response ready.
PORT cpu_cold_reset_pulse  OUT 1  Unified-reset trigger, 1 core clock.
PORT cpu_cold_reset_hold   OUT 1  Sticky CPU-hold level; survives CPU reset.
PORT cpu_ram_window_lg2    OUT 6  DDR RAM-window size select.
PORT cpu_mon_sense         OUT 7  DAFB monitor-sense code; bit 6 = extended-monitor flag.
PORT init_done_seen        IN  1  SoC reports DDR calibration done.
```

- [ ] **Step 3b: Write the parser**

Create `tools/debug/regmap.py`:

```python
#!/usr/bin/env python3
"""Parser for tools/debug/debug_regmap.def -- the single source of truth for the
CPU-side dbg_axi register map, capability bitmap, constants, and socket ports.

Standard library only. Import as `regmap` or run directly to dump a summary.
"""

import os


DEF_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "debug_regmap.def")

ACCESS_SET = ("RO", "RW", "W1P", "RAZWI", "MIXED")


def _int(tok):
    """Parse a decimal or 0x-prefixed hexadecimal token."""
    tok = tok.strip()
    if tok == "-":
        return None
    return int(tok, 16) if tok.lower().startswith("0x") else int(tok, 10)


class Reg(object):
    __slots__ = ("name", "offset", "access", "stage", "reset", "desc")

    def __init__(self, name, offset, access, stage, reset, desc):
        self.name, self.offset, self.access = name, offset, access
        self.stage, self.reset, self.desc = stage, reset, desc

    def __repr__(self):
        return "Reg(%s, 0x%05X)" % (self.name, self.offset)


class Blk(object):
    __slots__ = ("name", "base", "count", "stride", "access", "stage", "desc")

    def __init__(self, name, base, count, stride, access, stage, desc):
        self.name, self.base, self.count, self.stride = name, base, count, stride
        self.access, self.stage, self.desc = access, stage, desc

    def elements(self):
        """[(elementName, offset)] -- e.g. OFF_ARCH_D0 .. OFF_ARCH_D7."""
        return [("%s%d" % (self.name, i), self.base + i * self.stride)
                for i in range(self.count)]

    def __repr__(self):
        return "Blk(%s, 0x%05X x%d)" % (self.name, self.base, self.count)


class Range(object):
    __slots__ = ("name", "base", "size", "access", "stage", "desc")

    def __init__(self, name, base, size, access, stage, desc):
        self.name, self.base, self.size = name, base, size
        self.access, self.stage, self.desc = access, stage, desc

    def __repr__(self):
        return "Range(%s, 0x%05X+0x%X)" % (self.name, self.base, self.size)


class Feat(object):
    __slots__ = ("name", "bit", "stage", "desc")

    def __init__(self, name, bit, stage, desc):
        self.name, self.bit, self.stage, self.desc = name, bit, stage, desc

    def __repr__(self):
        return "Feat(%s, bit %d, stage %d)" % (self.name, self.bit, self.stage)


class Port(object):
    __slots__ = ("name", "direction", "width", "desc")

    def __init__(self, name, direction, width, desc):
        self.name, self.direction, self.width, self.desc = name, direction, width, desc

    def __repr__(self):
        return "Port(%s, %s, %d)" % (self.name, self.direction, self.width)


class RegMap(object):
    def __init__(self):
        self.regs = []
        self.blks = []
        self.ranges = []
        self.feats = []
        self.consts = {}
        self.ports = []

    def offsets(self):
        """name -> offset for every REG, every expanded BLK element, and every
        RANGE base. The RANGE body itself is reserved but has one named base."""
        out = {}
        for r in self.regs:
            out[r.name] = r.offset
        for b in self.blks:
            for name, off in b.elements():
                out[name] = off
        for rg in self.ranges:
            out[rg.name] = rg.base
        return out

    def features_for_stage(self, stage):
        """OR of (1 << bit) for every feature whose STAGE is <= `stage`.

        This is the machine-checked implementation of the spec's feature-honesty
        rule (section 3.4): a build declares its stage and CANNOT advertise a bit
        whose behaviour that stage has not implemented."""
        value = 0
        for f in self.feats:
            if f.stage <= stage:
                value |= (1 << f.bit)
        return value

    def reg(self, name):
        for r in self.regs:
            if r.name == name:
                return r
        raise KeyError(name)


def load(path=None):
    rm = RegMap()
    with open(path or DEF_PATH, "r") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            tok = line.split()
            kind = tok[0]
            try:
                if kind == "REG":
                    rm.regs.append(Reg(tok[1], _int(tok[2]), tok[3], _int(tok[4]),
                                       _int(tok[5]), " ".join(tok[6:])))
                elif kind == "BLK":
                    rm.blks.append(Blk(tok[1], _int(tok[2]), _int(tok[3]), _int(tok[4]),
                                       tok[5], _int(tok[6]), " ".join(tok[7:])))
                elif kind == "RANGE":
                    rm.ranges.append(Range(tok[1], _int(tok[2]), _int(tok[3]), tok[4],
                                           _int(tok[5]), " ".join(tok[6:])))
                elif kind == "FEAT":
                    rm.feats.append(Feat(tok[1], _int(tok[2]), _int(tok[3]),
                                         " ".join(tok[4:])))
                elif kind == "CONST":
                    rm.consts[tok[1]] = _int(tok[2])
                elif kind == "PORT":
                    rm.ports.append(Port(tok[1], tok[2], _int(tok[3]), " ".join(tok[4:])))
                else:
                    raise ValueError("unknown record kind %r" % kind)
            except (IndexError, ValueError) as exc:
                raise ValueError("%s:%d: %s (in %r)" % (path or DEF_PATH, lineno, exc, line))
    rm.feats.sort(key=lambda f: f.bit)
    return rm


if __name__ == "__main__":
    m = load()
    print("%d REG, %d BLK, %d RANGE, %d FEAT, %d CONST, %d PORT"
          % (len(m.regs), len(m.blks), len(m.ranges), len(m.feats),
             len(m.consts), len(m.ports)))
    print("features_for_stage(1) = 0x%08X" % m.features_for_stage(1))
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && python3 tools/debug/test_debug_regmap.py
```

Expected: every line prefixed `PASS`, final line `All checks passed.`, exit code 0.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add tools/debug/debug_regmap.def tools/debug/regmap.py tools/debug/test_debug_regmap.py
git commit -m "$(cat <<'EOF'
debug(stage0): machine-readable dbg_axi register/capability definition

Adds tools/debug/debug_regmap.def as the SINGLE source of truth for the
CPU-side debug/control slave: every frozen offset from the deployed
macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:438-719 localparam block, the
append-only capability bitmap (bits 0-18 deployed + 19-23 reserved by spec
section 3.4), the well-known constants, and the cpu_socket.vh port names.

Each record carries a STAGE column naming the implementation stage that makes
it real. features_for_stage(n) is therefore the machine-checked form of the
spec's feature-honesty rule: a build cannot advertise a bit whose behaviour it
has not implemented.

tools/debug/regmap.py parses it (standard library only -- this repo has no
pytest and no other Python). test_debug_regmap.py pins alignment, the 20-bit
window, offset uniqueness, dense/frozen feature numbering, the Stage 1 feature
value 0x0004000F, and the socket port surface.

Spec: docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md sections
3.2/3.4, Stage 0.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Cross-conformance against the deployed sibling contract

**Files:**
- Create: `tools/debug/test_sibling_conformance.py`
- Test: `tools/debug/test_sibling_conformance.py` (self-testing script)

**Interfaces:**
- Consumes: `tools/debug/regmap.py`'s `load()`, `RegMap.offsets()`, `RegMap.feats`, `RegMap.ports`.
- Produces: the proof that `debug_regmap.def` reserves **every** offset the deployed controller decodes and numbers **every** feature bit identically. Later tasks may treat `debug_regmap.def` as authoritative without re-deriving it.
- External inputs (read-only, never modified): `$MACQD700_SOC/cpu/rtl/core/debug/debug_ctrl.v`, `$MACQD700_SOC/tb/tests/host/fake_jtag_repl.py`, `$MACQD700_SOC/rtl/soc/cpu_socket.vh`, where `MACQD700_SOC` defaults to `/home/qwertyoruiop/macqd700-soc`.

- [ ] **Step 1: Write the failing test**

Create `tools/debug/test_sibling_conformance.py`:

```python
#!/usr/bin/env python3
"""Cross-conformance: tools/debug/debug_regmap.def vs the DEPLOYED sibling.

The compatibility contract this core must honour is not prose -- it is the
register map that macqd700-soc's debug_ctrl.v actually decodes and that
tools/jtag_repl.tcl (modelled offline by tb/tests/host/fake_jtag_repl.py)
actually addresses. This script proves three things:

  1. RESERVATION COMPLETENESS -- every offset the deployed RTL or the deployed
     host model uses is reserved somewhere in our definition. Spec section 3.2:
     "It must reserve them, return zero for absent functions, and never
     repurpose them."
  2. NAMING CONSISTENCY -- where both sides name the same register, they agree
     on its offset.
  3. FEATURE-BIT IDENTITY -- bits 0-18 carry the deployed numbering exactly.
     Spec section 3.4: "Bits 0-18 retain their deployed meanings and are never
     renumbered."

Plus the socket surface check that resolves spec section 14 decision 3.

Standard library only. Run:  python3 tools/debug/test_sibling_conformance.py
Exits 0 on success, 1 on failure, 77 when the sibling checkout is absent.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import regmap  # noqa: E402

SOC = os.environ.get("MACQD700_SOC", "/home/qwertyoruiop/macqd700-soc")
RTL = os.path.join(SOC, "cpu/rtl/core/debug/debug_ctrl.v")
FAKE = os.path.join(SOC, "tb/tests/host/fake_jtag_repl.py")
SOCKET = os.path.join(SOC, "rtl/soc/cpu_socket.vh")

# Deployed name -> our name, for the handful of registers where the two sides
# spell the same offset differently. Every entry is justified in-line.
NAME_ALIASES = {
    "OFF_BREAK_PC": "OFF_BREAK_PC0",              # RTL predates the 4-slot rename
    "OFF_HALT_EXC_MASK_0": "OFF_HALT_EXC_MASK0",  # RTL uses an underscore before the lane index
    "OFF_HALT_EXC_MASK_7": "OFF_HALT_EXC_MASK7",
    # The deployed map uses OFF_LIVE_A7 for the live ACTIVE A7 at 0x02108, so our live
    # A-register ARRAY cannot be called OFF_LIVE_A -- it would expand to a second,
    # different OFF_LIVE_A7 at 0x0214C. Both live arrays are therefore *REG-suffixed.
    "OFF_LIVE_D_BASE": "OFF_LIVE_DREG0",          # RTL names the array base, we name element 0
    "OFF_LIVE_A_TOP": "OFF_LIVE_AREG7",           # RTL names the array top, we name element 7
    "OFF_LIVE_D0": "OFF_LIVE_DREG0",              # host model's name for the same element
    "OFF_LIVE_A0": "OFF_LIVE_AREG0",
    "OFF_EXC_RING_BASE": "OFF_EXC_RING_BODY",     # RTL "base", spec section 3.2 calls it the ring body
}

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_rtl_offsets(path):
    """localparam [19:0] OFF_FOO = 20'h000A4;"""
    pat = re.compile(r"localparam\s*\[19:0\]\s*(OFF_[A-Z0-9_]+)\s*=\s*20'h([0-9A-Fa-f_]+)\s*;")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.search(line)
            if m:
                out[m.group(1)] = int(m.group(2).replace("_", ""), 16)
    return out


def parse_rtl_feats(path):
    """localparam integer FEAT_FOO = 7;  -> {"foo": 7}"""
    pat = re.compile(r"localparam\s+integer\s+FEAT_([A-Z0-9_]+)\s*=\s*(\d+)\s*;")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.search(line)
            if m:
                out[m.group(1).lower()] = int(m.group(2))
    return out


def parse_py_offsets(path):
    """OFF_FOO = 0x0A4  (module-level constants in fake_jtag_repl.py)"""
    pat = re.compile(r"^(OFF_[A-Z0-9_]+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*(#.*)?$")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.match(line.strip())
            if m:
                tok = m.group(2)
                out[m.group(1)] = int(tok, 16) if tok.lower().startswith("0x") else int(tok)
    return out


def main():
    missing = [p for p in (RTL, FAKE, SOCKET) if not os.path.exists(p)]
    if missing:
        sys.stderr.write(
            "SKIP: sibling checkout not found (set MACQD700_SOC). Missing: %s\n"
            % ", ".join(missing))
        return 77

    rm = regmap.load()
    ours = rm.offsets()
    our_values = set(ours.values())

    rtl_off = parse_rtl_offsets(RTL)
    py_off = parse_py_offsets(FAKE)
    check("parsed the deployed RTL offset table", len(rtl_off) >= 100,
          "only %d localparams" % len(rtl_off))
    check("parsed the deployed host-model offset table", len(py_off) >= 60,
          "only %d constants" % len(py_off))

    # 1. Reservation completeness.
    unreserved_rtl = sorted(
        "%s=0x%05X" % (n, o) for n, o in rtl_off.items() if o not in our_values)
    check("every DEPLOYED RTL offset is reserved in debug_regmap.def",
          not unreserved_rtl, ", ".join(unreserved_rtl))
    unreserved_py = sorted(
        "%s=0x%05X" % (n, o) for n, o in py_off.items() if o not in our_values)
    check("every DEPLOYED host-model offset is reserved in debug_regmap.def",
          not unreserved_py, ", ".join(unreserved_py))

    # 2. Naming consistency, for names present on both sides.
    mismatches = []
    for src_name, table in (("rtl", rtl_off), ("host", py_off)):
        for name, off in sorted(table.items()):
            ours_name = NAME_ALIASES.get(name, name)
            if ours_name in ours and ours[ours_name] != off:
                mismatches.append("%s %s: deployed 0x%05X vs ours 0x%05X"
                                  % (src_name, name, off, ours[ours_name]))
    check("shared register names agree on their offset", not mismatches,
          "; ".join(mismatches))

    # 3. Feature-bit identity for the deployed bits 0-18.
    rtl_feats = parse_rtl_feats(RTL)
    ours_feats = {f.name: f.bit for f in rm.feats}
    feat_bad = sorted("%s: deployed %d vs ours %r" % (n, b, ours_feats.get(n))
                      for n, b in rtl_feats.items() if ours_feats.get(n) != b)
    check("deployed feature-bit numbering is reproduced exactly",
          not feat_bad, "; ".join(feat_bad))
    check("all 19 deployed feature bits are present",
          len(rtl_feats) == 19, "found %d" % len(rtl_feats))

    # 4. Socket surface -- spec section 14 decision 3.
    with open(SOCKET) as fh:
        socket_text = fh.read()
    absent = sorted(p.name for p in rm.ports if p.name not in socket_text)
    check("every PORT name appears verbatim in cpu_socket.vh", not absent,
          repr(absent))
    check("CPU_SOCKET_DBG_AW is 20 in cpu_socket.vh",
          "`define CPU_SOCKET_DBG_AW 20" in socket_text)
    check("CPU_SOCKET_DBG_DW is 32 in cpu_socket.vh",
          "`define CPU_SOCKET_DBG_DW 32" in socket_text)
    check("our DBG_AW/DBG_DW constants match the socket",
          rm.consts["DBG_AW"] == 20 and rm.consts["DBG_DW"] == 32)

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && python3 tools/debug/test_sibling_conformance.py
```

Expected before the file exists: `python3: can't open file ... No such file or directory` (exit 2). After creating it in Step 1 this step is the first real run; the test is written to pass against the `.def` file Task 1 already landed, so the honest failing-first evidence for this task is obtained by running it against a deliberately damaged copy in Step 3.

- [ ] **Step 3: Prove the test can actually fail**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
cp tools/debug/debug_regmap.def /tmp/regmap_backup.def
sed -i 's/^REG OFF_MON_SENSE          0x0005C/REG OFF_MON_SENSE          0x00064/' tools/debug/debug_regmap.def
python3 tools/debug/test_sibling_conformance.py; echo "exit=$?"
cp /tmp/regmap_backup.def tools/debug/debug_regmap.def
rm -f /tmp/regmap_backup.def
```

Expected: `FAIL  every DEPLOYED RTL offset is reserved in debug_regmap.def  -- OFF_MON_SENSE=0x0005C`, plus `FAIL  shared register names agree on their offset`, and `exit=1`. (The Task-1 self-check would also flag the resulting duplicate at `0x00064`.)

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
python3 tools/debug/test_debug_regmap.py && python3 tools/debug/test_sibling_conformance.py
```

Expected: both scripts print only `PASS` lines and `All checks passed.`, exit code 0. If the second prints `SKIP` and exits 77, the sibling checkout is missing — resolve that before continuing; this check is not optional.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add tools/debug/test_sibling_conformance.py
git commit -m "$(cat <<'EOF'
debug(stage0): cross-conformance of the register map against the deployed sibling

Proves debug_regmap.def against the two authorities that actually define the
compatibility contract:
  * macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v localparam offset + feature-bit
    tables (the RTL that is deployed on hardware today), and
  * macqd700-soc/tb/tests/host/fake_jtag_repl.py OFF_* constants (the offline
    model of tools/jtag_repl.tcl that the GDB bridge is tested against).

Three invariants: every deployed offset is RESERVED in our namespace (spec
3.2), shared names agree on their offset, and the deployed feature-bit
numbering is reproduced exactly (spec 3.4 -- "never renumbered"). Also checks
every socket port name appears verbatim in rtl/soc/cpu_socket.vh and that
CPU_SOCKET_DBG_AW/DW are 20/32.

Read-only with respect to the sibling checkout; skips with exit 77 when
MACQD700_SOC is absent.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Resolve spec §14 decision #3 and land the addendum

**Files:**
- Modify: `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` (Status line at lines 3-4; append a new §15 after the current final line 883)
- Test: `tools/debug/test_sibling_conformance.py` (already pins the port names; this task adds the human-readable decision record and the Stage-1 behaviour decisions the RTL tasks depend on)

**Interfaces:**
- Consumes: the `PORT` records validated in Task 2.
- Produces: spec §15 — the authoritative, citable statement of (a) the socket/reset port naming convention, (b) which CONTROL/STATUS bits Stage 1 implements versus makes RAZ/WI, (c) what `cfg_wipe` restores, (d) how `DBG_BUILD_ID` is supplied, (e) the CPU-reset counter's saturation rule. Tasks 7-11 cite §15 rather than re-deciding.

- [ ] **Step 1: Write the failing check**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -c '^## 15\.' docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md
```

Expected: `0` (exit 1) — §15 does not exist yet, so §14 decision #3 is still open.

- [ ] **Step 2: Flip the Status line**

Edit `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`, replacing lines 3-4:

```markdown
**Status:** PROPOSED ARCHITECTURE ADDENDUM / DESIGN ONLY. No RTL or host tool is
implemented by this document.
```

with:

```markdown
**Status:** ACCEPTED ARCHITECTURE ADDENDUM for **Stages 0-1** (integration decisions
resolved in §15, 2026-08-17). Stages 2-7 remain PROPOSED / DESIGN ONLY. Implementation
plan: `docs/superpowers/plans/2026-08-17-debug-ctrl-stage0-stage1.md`.
```

- [ ] **Step 3: Append §15**

Append to the end of `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`:

```markdown

---

## 15. Resolved integration decisions for Stages 0-1 (2026-08-17)

This section resolves the parts of §14 that block Stage 0/1 and records the
Stage-1 behavioural choices that the implementation plan depends on. §14 items
1, 2, 4 and 5 remain open; they block Stage 2 or later and are deliberately not
decided here.

### 15.1 §14 decision 3 — SoC socket and reset port naming (RESOLVED)

The core exports the socket names **verbatim**, forced onto the generated
Verilog with SpinalHDL `setName()`, so `macqd700-soc` binds them with no rename
shim. The authority is `macqd700-soc/rtl/soc/cpu_socket.vh` §4 (lines 145-162)
and §6 (lines 170-176); the names are additionally machine-checked against that
file by `tools/debug/test_sibling_conformance.py`.

| Core-side port | Dir | Width | Socket authority |
|---|---|---:|---|
| `dbg_axi_awaddr` / `awvalid` / `awready` | in/in/out | 20/1/1 | `cpu_socket.vh:146-148` |
| `dbg_axi_wdata` / `wstrb` / `wvalid` / `wready` | in/in/in/out | 32/4/1/1 | `cpu_socket.vh:149-152` |
| `dbg_axi_bresp` / `bvalid` / `bready` | out/out/in | 2/1/1 | `cpu_socket.vh:153-155` |
| `dbg_axi_araddr` / `arvalid` / `arready` | in/in/out | 20/1/1 | `cpu_socket.vh:156-158` |
| `dbg_axi_rdata` / `rresp` / `rvalid` / `rready` | out/out/out/in | 32/2/1/1 | `cpu_socket.vh:159-162` |
| `cpu_cold_reset_pulse` | out | 1 | `cpu_socket.vh:171` |
| `cpu_cold_reset_hold` | out | 1 | `cpu_socket.vh:172` |
| `cpu_ram_window_lg2` | out | 6 | `cpu_socket.vh:173` |
| `cpu_mon_sense` | out | 7 | `cpu_socket.vh:174` |
| `init_done_seen` | in | 1 | `cpu_socket.vh:176` |

There is **no** `AWPROT`/`ARPROT` on this interface. `cpu_socket.vh:145-162` does
not declare them, so the standard SpinalHDL `AxiLite4` bundle (which does) is not
used; a dedicated `DbgAxiLite` bundle carries exactly the socket's signal set.

**Debug power-on reset.** The socket has *no* board-level POR input
(`cpu_socket.vh:95-97` gives only `clk` and `rst`). The core therefore generates
its own, exactly as `macqd700-soc/cpu/rtl/core/debug/debug_reset_ctl.v` does: a
counter of deliberately reset-less flops whose only initial value is the FPGA
configuration INIT, producing a `POR_CYCLES`-long pulse after configuration and
then deasserting forever. In SpinalHDL this is a derived `ClockDomain` with
`resetKind = BOOT`. `debug_reset_ctl.v`'s header states the requirement this
satisfies: "dbg_rst … Asserted for POR_CYCLES clocks after FPGA configuration,
then deasserted FOREVER -- it is NOT a function of cpu_rst." A POR pulse rather
than bare INIT is mandatory because the debug domain holds registers with
non-zero reset values (`cpu_ram_window_lg2` = 26, `cpu_mon_sense` = 7'h06).
`POR_CYCLES` default is 16, matching the sibling module's default.

**CPU-reset notification.** The core reset is *observed*, never consumed, by the
debug domain: a rising-edge detector on the socket `rst`, clocked in the debug
domain, with reset value 1 so a `rst` already high when the debug domain leaves
POR does not manufacture a spurious edge (`debug_reset_ctl.v:129-139`). No extra
socket port is added.

**Build ID.** `DBG_BUILD_ID` remains SoC-supplied (§3.1). It enters the core as a
`DebugCtrlPlugin` constructor parameter, defaulting to `0x00000000`, which the
Verilog generator may override from the environment. It is deliberately NOT a
socket port and NOT a `Global` database key (§0.9).

**Init-done observation.** `init_done_seen` is a level input; the debug domain
latches it sticky so a debugger attaching after DDR calibration still sees it,
and `OFF_CONTROL` bit 3 (init-done override) ORs into the same status bit. Both
live in the debug reset domain and therefore survive CPU reset.

### 15.2 Stage-1 CONTROL and STATUS behaviour

Stage 1 has no halt/step machinery, so it must not *pretend* to. Per-bit
behaviour, chosen so a host discovers the truth by reading back rather than by
waiting for a status bit that will never set:

| CONTROL bit | Stage-1 behaviour |
|---:|---|
| 0 manual halt request | **RAZ/WI** — write ignored, reads 0 (Stage 2 implements it) |
| 1 single-step pulse | **RAZ/WI** (Stage 2) |
| 2 deprecated reset-pulse alias | real: aliases bit 5 |
| 3 init-done override level | real |
| 4 cold-reset hold level | real, drives `cpu_cold_reset_hold`, survives CPU reset |
| 5 cold-reset pulse | real, drives a 1-cycle `cpu_cold_reset_pulse` |
| 6 reserved | reads 0 |
| 7 legacy step-arm observation | **RAZ/WI** (Stage 2) |

`OFF_STATUS` in Stage 1: bit 0 `halted` = 0, bit 1 `exception pending` = 0, bit 2
`init_done_seen` = real, bit 3 `running` = 1, bit 4 `auto-halt latched` = 0. This
keeps §3.3's "`halted` and `running` are mutually exclusive" true.

**Known gap, deliberately accepted:** the frozen contract has no discovery bit
for manual halt itself, so a Stage-1 build is distinguishable from a
halt-capable one only by `OFF_VERSION` (`0xDEB6_0100`) and by CONTROL bit 0
reading back 0 after a write. A new append-only bit for basic stop/step should
be minted when Stage 2 lands, and this is recorded as an open item rather than
worked around.

### 15.3 `cfg_wipe` scope (feature bit 2)

`OFF_DBG_RESET_CTL` bit 0 restores host configuration to POR defaults **without
resetting the AXI slave FSM**, so the very write that requested the wipe still
receives its `B` response. This copies `debug_ctrl.v:3016-3024` verbatim,
including its explicit contrast with a true debug-domain reset ("that DOES
disturb the AXI FSM and is therefore fire-and-forget from the host's point of
view"). In Stage 1 the wipe restores `cpu_ram_window_lg2` to 26 and
`cpu_mon_sense` to `0x06` and nothing else. It deliberately does **not** clear
`cold_reset_hold` or the init-done override, because the deployed wipe list
(`debug_ctrl.v:3024-3062`) does not clear them either, and feature bit 2 must
mean the same thing on both cores.

### 15.4 CPU-reset counter

`OFF_DBG_RESET_CTL[31:16]` is a 16-bit count of observed CPU-reset rising edges,
living in the debug reset domain (so it survives the resets it counts), cleared
by writing bit 1. It **saturates** at `0xFFFF` rather than wrapping: zero is a
meaningful value ("no reset observed since clear") and must not be reachable by
wraparound.

### 15.5 Stage-1 feature value

`OFF_FEATURES` reads `0x0004000F` — bits 0 `dbg_reset_domain`, 1
`axi_ready_gated`, 2 `cfg_wipe`, 3 `cpu_reset_count`, 18 `mon_sense`. Every other
bit reads 0. This value is not written by hand anywhere: it is computed from the
`STAGE` column of `tools/debug/debug_regmap.def`, which is the machine-checked
form of §3.4's "No feature bit may advertise a tied-off counter, stale shadow,
placeholder probe, or operation that can be silently dropped."
```

- [ ] **Step 4: Run the checks to verify they pass**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -c '^## 15\.' docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md
grep -n '^\*\*Status:\*\* ACCEPTED' docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md
python3 tools/debug/test_sibling_conformance.py
```

Expected: `1`; a hit on the ACCEPTED status line; and `All checks passed.` from the conformance script.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md
git commit -m "$(cat <<'EOF'
docs(spec): resolve section 14 decision 3 and accept the addendum for Stages 0-1

Adds section 15 to the debug_ctrl design spec, resolving the ONE open
integration decision that blocked Stage 0/1: the SoC socket and reset port
naming. The core exports cpu_socket.vh's names verbatim via SpinalHDL
setName() (no rename shim, no AxPROT -- the socket declares none), generates
its own debug power-on reset from reset-less flops exactly as the sibling's
debug_reset_ctl.v does, OBSERVES the CPU reset as a rising edge rather than
consuming it, and takes DBG_BUILD_ID as a constructor parameter rather than a
socket port or a Global key.

Also records the Stage-1 behavioural decisions the RTL tasks depend on:
per-bit CONTROL/STATUS behaviour (halt/step bits are RAZ/WI, not lies), the
cfg_wipe scope copied from debug_ctrl.v:3016-3062, the saturating CPU-reset
counter, and the Stage-1 feature value 0x0004000F computed from the register
map's STAGE column rather than hand-written.

Section 14 items 1, 2, 4, 5 remain open -- they block Stage 2 or later.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Generator for the Scala / Tcl / Python constant files

**Files:**
- Create: `tools/debug/gen_debug_regmap.py`
- Create (generated, committed): `src/main/scala/m68k040/debug/DebugRegMap.scala`
- Create (generated, committed): `tools/debug/debug_regmap.py`
- Create (generated, committed): `tools/debug/debug_regmap.tcl`
- Test: `tools/debug/gen_debug_regmap.py --check`

**Interfaces:**
- Consumes: `regmap.load()` and the `RegMap`/`Reg`/`Blk`/`Range`/`Feat`/`Port` API from Task 1.
- Produces, and every later task depends on these exact signatures:
  - Scala `object m68k040.debug.DebugRegMap` with `val DBG_AW: Int`, `val DBG_DW: Int`, `val VERSION_VALUE: BigInt`, `val RAM_WINDOW_LG2_POR: Int`, `val RAM_WINDOW_LG2_MIN: Int`, `val RAM_WINDOW_LG2_MAX: Int`, `val MON_SENSE_POR: Int`, `val POR_CYCLES_DEFAULT: Int`, one `val OFF_xxx: Int` per offset, `val allOffsets: Seq[(String, Int)]`, `val features: Seq[(String, Int, Int)]` (name, bit, stage), `val ports: Seq[(String, String, Int)]` (name, direction, width), and `def featuresForStage(stage: Int): BigInt`.
  - Python module `tools/debug/debug_regmap.py` with module-level `OFF_*`, `ALL_OFFSETS: dict`, `FEATURES: dict[str, tuple[int, int]]`, `PORTS: dict[str, tuple[str, int]]`, `CONSTS: dict[str, int]`, and `features_for_stage(stage) -> int`.
  - Tcl file `tools/debug/debug_regmap.tcl` with `::dbg::OFF(<NAME>)`, `::dbg::FEAT_BIT(<name>)`, `::dbg::FEAT_STAGE(<name>)`, `::dbg::PORT_DIR(<name>)`, `::dbg::PORT_WIDTH(<name>)`, `::dbg::CONST(<NAME>)`, and `proc ::dbg::features_for_stage {stage}`.

- [ ] **Step 1: Write the failing test**

The generator is its own test: `--check` regenerates in memory and diffs against the committed files. Create `tools/debug/gen_debug_regmap.py`:

```python
#!/usr/bin/env python3
"""Generate the Scala / Python / Tcl constant files from debug_regmap.def.

    python3 tools/debug/gen_debug_regmap.py            # write the files
    python3 tools/debug/gen_debug_regmap.py --check    # verify they are current

--check is the drift gate: it regenerates every output in memory and diffs it
against what is on disk, exiting 1 (with a unified diff) if any file is stale.
The register map has exactly one source of truth; a hand-edited generated file
is a contract violation, not a convenience.

Standard library only.
"""

import argparse
import difflib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import regmap  # noqa: E402

BANNER_LINES = (
    "GENERATED FILE -- DO NOT EDIT BY HAND.",
    "Source:     tools/debug/debug_regmap.def",
    "Regenerate: python3 tools/debug/gen_debug_regmap.py",
    "Verify:     python3 tools/debug/gen_debug_regmap.py --check",
)

SCALA_OUT = os.path.join(REPO, "src/main/scala/m68k040/debug/DebugRegMap.scala")
PY_OUT = os.path.join(HERE, "debug_regmap.py")
TCL_OUT = os.path.join(HERE, "debug_regmap.tcl")


def sorted_offsets(rm):
    """[(name, offset)] ordered by offset then name -- a stable emission order."""
    return sorted(rm.offsets().items(), key=lambda kv: (kv[1], kv[0]))


def const_lit(value):
    """Small constants read as decimal (a width of 20 is not '0x14'); anything that is
    really a bit pattern reads as hex."""
    return "%d" % value if value < 256 else "0x%X" % value


def gen_scala(rm):
    out = []
    for line in BANNER_LINES:
        out.append("// " + line)
    out.append("")
    out.append("package m68k040.debug")
    out.append("")
    out.append("/** Frozen offsets, capability bitmap, constants and socket ports of the")
    out.append("  * CPU-side `dbg_axi` debug/control slave. See the design spec")
    out.append("  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`")
    out.append("  * sections 3.2/3.4 and 15. */")
    out.append("object DebugRegMap {")
    out.append("  // -- constants --------------------------------------------------------")
    for name in sorted(rm.consts):
        value = rm.consts[name]
        if value >= (1 << 31):
            out.append('  val %s: BigInt = BigInt("%08X", 16)' % (name, value))
        else:
            out.append("  val %s: Int = %s" % (name, const_lit(value)))
    out.append("")
    out.append("  // -- offsets ----------------------------------------------------------")
    for name, off in sorted_offsets(rm):
        out.append("  val %s: Int = 0x%05X" % (name, off))
    out.append("")
    out.append("  /** Every reserved offset: (name, offset), ordered by offset. */")
    out.append("  val allOffsets: Seq[(String, Int)] = Seq(")
    for name, off in sorted_offsets(rm):
        out.append('    ("%s", 0x%05X),' % (name, off))
    out.append("  )")
    out.append("")
    out.append("  /** Capability bitmap: (name, bit, stage-that-makes-it-real). */")
    out.append("  val features: Seq[(String, Int, Int)] = Seq(")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append('    ("%s", %d, %d),' % (f.name, f.bit, f.stage))
    out.append("  )")
    out.append("")
    out.append("  /** OR of (1 << bit) for every feature this stage genuinely implements.")
    out.append("    * This IS the spec section 3.4 honesty rule in executable form: a build")
    out.append("    * cannot advertise a bit whose behaviour it has not built. */")
    out.append("  def featuresForStage(stage: Int): BigInt =")
    out.append("    features.filter(_._3 <= stage)")
    out.append("            .foldLeft(BigInt(0))((acc, f) => acc | (BigInt(1) << f._2))")
    out.append("")
    out.append("  /** Socket ports, verbatim from macqd700-soc/rtl/soc/cpu_socket.vh:")
    out.append("    * (name, direction as seen from the CPU, width). */")
    out.append("  val ports: Seq[(String, String, Int)] = Seq(")
    for p in rm.ports:
        out.append('    ("%s", "%s", %d),' % (p.name, p.direction, p.width))
    out.append("  )")
    out.append("}")
    out.append("")
    return "\n".join(out)


def gen_python(rm):
    out = []
    out.append('"""' + BANNER_LINES[0])
    for line in BANNER_LINES[1:]:
        out.append(line)
    out.append('"""')
    out.append("")
    out.append("# -- constants ------------------------------------------------------------")
    for name in sorted(rm.consts):
        out.append("%s = %s" % (name, const_lit(rm.consts[name])))
    out.append("")
    out.append("CONSTS = {")
    for name in sorted(rm.consts):
        out.append('    "%s": %s,' % (name, const_lit(rm.consts[name])))
    out.append("}")
    out.append("")
    out.append("# -- offsets --------------------------------------------------------------")
    for name, off in sorted_offsets(rm):
        out.append("%s = 0x%05X" % (name, off))
    out.append("")
    out.append("ALL_OFFSETS = {")
    for name, off in sorted_offsets(rm):
        out.append('    "%s": 0x%05X,' % (name, off))
    out.append("}")
    out.append("")
    out.append("# -- capability bitmap: name -> (bit, stage-that-makes-it-real) ------------")
    out.append("FEATURES = {")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append('    "%s": (%d, %d),' % (f.name, f.bit, f.stage))
    out.append("}")
    out.append("")
    out.append("# -- socket ports: name -> (direction from the CPU, width) ----------------")
    out.append("PORTS = {")
    for p in rm.ports:
        out.append('    "%s": ("%s", %d),' % (p.name, p.direction, p.width))
    out.append("}")
    out.append("")
    out.append("")
    out.append("def features_for_stage(stage):")
    out.append('    """OR of (1 << bit) for every feature this stage genuinely implements."""')
    out.append("    value = 0")
    out.append("    for _name, (bit, feat_stage) in FEATURES.items():")
    out.append("        if feat_stage <= stage:")
    out.append("            value |= (1 << bit)")
    out.append("    return value")
    out.append("")
    return "\n".join(out)


def gen_tcl(rm):
    out = []
    for line in BANNER_LINES:
        out.append("# " + line)
    out.append("")
    out.append("namespace eval ::dbg {")
    out.append("    variable OFF")
    out.append("    variable CONST")
    out.append("    variable FEAT_BIT")
    out.append("    variable FEAT_STAGE")
    out.append("    variable PORT_DIR")
    out.append("    variable PORT_WIDTH")
    out.append("}")
    out.append("")
    for name in sorted(rm.consts):
        out.append("set ::dbg::CONST(%s) %s" % (name, const_lit(rm.consts[name])))
    out.append("")
    for name, off in sorted_offsets(rm):
        out.append("set ::dbg::OFF(%s) 0x%05X" % (name, off))
    out.append("")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append("set ::dbg::FEAT_BIT(%s) %d" % (f.name, f.bit))
        out.append("set ::dbg::FEAT_STAGE(%s) %d" % (f.name, f.stage))
    out.append("")
    for p in rm.ports:
        out.append("set ::dbg::PORT_DIR(%s) %s" % (p.name, p.direction))
        out.append("set ::dbg::PORT_WIDTH(%s) %d" % (p.name, p.width))
    out.append("")
    out.append("# OR of (1 << bit) for every feature the given stage genuinely implements.")
    out.append("proc ::dbg::features_for_stage {stage} {")
    out.append("    variable FEAT_BIT")
    out.append("    variable FEAT_STAGE")
    out.append("    set value 0")
    out.append("    foreach name [array names FEAT_BIT] {")
    out.append("        if {$FEAT_STAGE($name) <= $stage} {")
    out.append("            set value [expr {$value | (1 << $FEAT_BIT($name))}]")
    out.append("        }")
    out.append("    }")
    out.append("    return $value")
    out.append("}")
    out.append("")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true",
                    help="verify the committed files are current; do not write")
    args = ap.parse_args()

    rm = regmap.load()
    wanted = ((SCALA_OUT, gen_scala(rm)),
              (PY_OUT, gen_python(rm)),
              (TCL_OUT, gen_tcl(rm)))

    stale = 0
    for path, text in wanted:
        if args.check:
            have = ""
            if os.path.exists(path):
                with open(path) as fh:
                    have = fh.read()
            if have != text:
                stale += 1
                sys.stderr.write("STALE: %s\n" % os.path.relpath(path, REPO))
                for line in difflib.unified_diff(
                        have.splitlines(True), text.splitlines(True),
                        fromfile="on-disk", tofile="generated", n=2):
                    sys.stderr.write(line if line.endswith("\n") else line + "\n")
            else:
                print("CURRENT: %s" % os.path.relpath(path, REPO))
        else:
            directory = os.path.dirname(path)
            if not os.path.isdir(directory):
                os.makedirs(directory)
            with open(path, "w") as fh:
                fh.write(text)
            print("WROTE: %s" % os.path.relpath(path, REPO))

    if args.check and stale:
        sys.stderr.write("\n%d generated file(s) are stale -- run "
                         "python3 tools/debug/gen_debug_regmap.py\n" % stale)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
python3 tools/debug/gen_debug_regmap.py --check; echo "exit=$?"
```

Expected: three `STALE:` blocks (the three output files do not exist yet, so the diff is the entire generated content) and `exit=1`.

- [ ] **Step 3: Generate the files**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && python3 tools/debug/gen_debug_regmap.py
```

Expected:
```
WROTE: src/main/scala/m68k040/debug/DebugRegMap.scala
WROTE: tools/debug/debug_regmap.py
WROTE: tools/debug/debug_regmap.tcl
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
python3 tools/debug/gen_debug_regmap.py --check
python3 -c "import sys; sys.path.insert(0, 'tools/debug'); import debug_regmap as d; print(hex(d.features_for_stage(1)), hex(d.OFF_DBG_RESET_CTL), hex(d.VERSION_VALUE))"
tclsh -c 'source tools/debug/debug_regmap.tcl; puts [format 0x%08X [::dbg::features_for_stage 1]]' 2>/dev/null || echo "(tclsh absent -- Tcl file is consumed by the sibling REPL, not by this repo's CI)"
make SBT=~/sbt/bin/sbt compile
```

Expected: three `CURRENT:` lines; `0x4000f 0xa4 0xdeb60100`; and a clean `sbt compile` proving the generated Scala is syntactically valid and `DebugRegMap` resolves.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add tools/debug/gen_debug_regmap.py tools/debug/debug_regmap.py tools/debug/debug_regmap.tcl src/main/scala/m68k040/debug/DebugRegMap.scala
git commit -m "$(cat <<'EOF'
debug(stage0): generate Scala/Python/Tcl constants from the register map

One definition, three consumers. tools/debug/gen_debug_regmap.py emits
m68k040.debug.DebugRegMap (Scala, used by the RTL), tools/debug/debug_regmap.py
(host tooling) and tools/debug/debug_regmap.tcl (jtag_repl.tcl-side), and
--check regenerates them in memory and diffs against the committed copies so a
hand-edited generated file fails loudly instead of silently forking the
contract.

featuresForStage(stage) is emitted in all three languages: it is the executable
form of the spec's feature-honesty rule, computing OFF_FEATURES from the STAGE
column rather than from a hand-maintained literal.

Spec: sections 3.2/3.4, Stage 0 "generate or compare the Scala RTL constants,
Tcl constants, and Python constants against it".

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Scala-side conformance test

**Files:**
- Create: `src/test/scala/m68k040/debug/DebugRegMapSpec.scala`
- Test: `src/test/scala/m68k040/debug/DebugRegMapSpec.scala`

**Interfaces:**
- Consumes: `m68k040.debug.DebugRegMap` (`allOffsets`, `features`, `ports`, `featuresForStage`, `DBG_AW`, `DBG_DW`, `VERSION_VALUE`, `RAM_WINDOW_LG2_POR`, `RAM_WINDOW_LG2_MIN`, `RAM_WINDOW_LG2_MAX`, `MON_SENSE_POR`, `POR_CYCLES_DEFAULT`) and the on-disk `tools/debug/debug_regmap.def`.
- Produces: an in-`fastTest` guarantee that the checked-in Scala constants still match the definition file, so no RTL task can drift from the contract without a red test. Runs untagged, i.e. inside `make test-fast`.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/DebugRegMapSpec.scala`:

```scala
package m68k040.debug

import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source

/** Guards the ONE source of truth. `tools/debug/debug_regmap.def` defines the frozen
  * dbg_axi offsets, the append-only capability bitmap, the well-known constants and the
  * socket port surface; `DebugRegMap.scala` is GENERATED from it by
  * `tools/debug/gen_debug_regmap.py`. This spec re-parses the definition file and proves
  * the generated Scala still matches, so a stale generated file cannot reach synthesis.
  *
  * It deliberately re-implements the (tiny) parser rather than shelling out to Python:
  * the check must run inside `make test-fast` on any machine, with no interpreter
  * assumption beyond the JVM. */
class DebugRegMapSpec extends AnyFunSuite {

  private val defPath = "tools/debug/debug_regmap.def"

  private def num(tok: String): Int =
    if (tok.toLowerCase.startsWith("0x")) Integer.parseInt(tok.substring(2), 16)
    else tok.toInt

  /** (offsets, features as (name, bit, stage), consts) parsed from the .def file. */
  private lazy val parsed: (Map[String, Int], Seq[(String, Int, Int)], Map[String, BigInt],
                            Seq[(String, String, Int)]) = {
    val src = Source.fromFile(defPath, "UTF-8")
    try {
      val offsets = scala.collection.mutable.LinkedHashMap[String, Int]()
      val feats   = scala.collection.mutable.ArrayBuffer[(String, Int, Int)]()
      val consts  = scala.collection.mutable.LinkedHashMap[String, BigInt]()
      val ports   = scala.collection.mutable.ArrayBuffer[(String, String, Int)]()
      for (raw <- src.getLines()) {
        val line = raw.split("#", 2)(0).trim
        if (line.nonEmpty) {
          val t = line.split("\\s+").toVector
          t(0) match {
            case "REG"   => offsets(t(1)) = num(t(2))
            case "BLK"   =>
              val base = num(t(2)); val count = num(t(3)); val stride = num(t(4))
              for (i <- 0 until count) offsets(s"${t(1)}$i") = base + i * stride
            case "RANGE" => offsets(t(1)) = num(t(2))
            case "FEAT"  => feats += ((t(1), num(t(2)), num(t(3))))
            case "CONST" =>
              consts(t(1)) = if (t(2).toLowerCase.startsWith("0x")) BigInt(t(2).substring(2), 16)
                             else BigInt(t(2))
            case "PORT"  => ports += ((t(1), t(2), num(t(3))))
            case other   => fail(s"unknown record kind '$other' in $defPath: $line")
          }
        }
      }
      (offsets.toMap, feats.toSeq, consts.toMap, ports.toSeq)
    } finally src.close()
  }

  test("the definition file is present and non-trivial") {
    val (offsets, feats, consts, ports) = parsed
    assert(offsets.size >= 100, s"only ${offsets.size} offsets parsed from $defPath")
    assert(feats.size == 24, s"expected 24 feature bits, got ${feats.size}")
    assert(consts.size >= 7, s"only ${consts.size} constants")
    assert(ports.size == 22, s"expected 22 socket ports, got ${ports.size}")
  }

  test("generated DebugRegMap.allOffsets matches the definition file exactly") {
    val (offsets, _, _, _) = parsed
    val generated = DebugRegMap.allOffsets.toMap
    val missing = (offsets.keySet -- generated.keySet).toSeq.sorted
    val extra   = (generated.keySet -- offsets.keySet).toSeq.sorted
    assert(missing.isEmpty, s"DebugRegMap.scala is STALE -- missing: ${missing.mkString(", ")}")
    assert(extra.isEmpty,   s"DebugRegMap.scala is STALE -- extra: ${extra.mkString(", ")}")
    val differing = offsets.filter { case (n, o) => generated(n) != o }
      .map { case (n, o) => f"$n: def 0x$o%05X vs scala 0x${generated(n)}%05X" }.toSeq.sorted
    assert(differing.isEmpty, s"offset drift: ${differing.mkString("; ")}")
  }

  test("generated DebugRegMap.features matches the definition file exactly") {
    val (_, feats, _, _) = parsed
    assert(DebugRegMap.features.sortBy(_._2) == feats.sortBy(_._2),
      s"DebugRegMap.scala is STALE -- def=${feats.sortBy(_._2)} scala=${DebugRegMap.features.sortBy(_._2)}")
  }

  test("generated DebugRegMap.ports matches the definition file exactly") {
    val (_, _, _, ports) = parsed
    assert(DebugRegMap.ports == ports,
      s"DebugRegMap.scala is STALE -- def=$ports scala=${DebugRegMap.ports}")
  }

  test("generated constants match the definition file") {
    val (_, _, consts, _) = parsed
    assert(BigInt(DebugRegMap.DBG_AW)             == consts("DBG_AW"))
    assert(BigInt(DebugRegMap.DBG_DW)             == consts("DBG_DW"))
    assert(DebugRegMap.VERSION_VALUE              == consts("VERSION_VALUE"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_POR) == consts("RAM_WINDOW_LG2_POR"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_MIN) == consts("RAM_WINDOW_LG2_MIN"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_MAX) == consts("RAM_WINDOW_LG2_MAX"))
    assert(BigInt(DebugRegMap.MON_SENSE_POR)      == consts("MON_SENSE_POR"))
    assert(BigInt(DebugRegMap.POR_CYCLES_DEFAULT) == consts("POR_CYCLES_DEFAULT"))
  }

  test("the frozen offsets that Stage 1 decodes are at their deployed addresses") {
    // Spot-checks against macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:438-509 so a
    // silent renumbering of the block Stage 1 actually implements cannot pass.
    assert(DebugRegMap.OFF_VERSION        == 0x00000)
    assert(DebugRegMap.OFF_BUILD_ID       == 0x00004)
    assert(DebugRegMap.OFF_CONTROL        == 0x00008)
    assert(DebugRegMap.OFF_STATUS         == 0x0000C)
    assert(DebugRegMap.OFF_RAM_WINDOW_LG2 == 0x00058)
    assert(DebugRegMap.OFF_MON_SENSE      == 0x0005C)
    assert(DebugRegMap.OFF_FEATURES       == 0x000A0)
    assert(DebugRegMap.OFF_DBG_RESET_CTL  == 0x000A4)
    assert(DebugRegMap.OFF_CAP_TRACE      == 0x000A8)
  }

  test("featuresForStage is honest: Stage 1 advertises exactly 0x0004000F") {
    assert(DebugRegMap.featuresForStage(0) == BigInt(0))
    assert(DebugRegMap.featuresForStage(1) == BigInt(0x0004000FL),
      f"got 0x${DebugRegMap.featuresForStage(1)}%08X")
    // Monotonic: a later stage never retracts an earlier stage's bit.
    for (s <- 1 until 8) {
      val lower = DebugRegMap.featuresForStage(s - 1)
      val upper = DebugRegMap.featuresForStage(s)
      assert((lower & upper) == lower, s"stage $s retracts a bit advertised by stage ${s - 1}")
    }
    // Every bit advertised at stage 1 has a FEAT record whose stage really is <= 1.
    val advertised = DebugRegMap.featuresForStage(1)
    for ((name, bit, stage) <- DebugRegMap.features if ((advertised >> bit) & 1) == 1)
      assert(stage <= 1, s"feature '$name' (bit $bit) is advertised at stage 1 but is stage $stage")
  }

  test("offsets are 32-bit aligned, unique, and inside the 20-bit window") {
    val all = DebugRegMap.allOffsets
    assert(all.forall(_._2 % 4 == 0), "an offset is not 32-bit aligned")
    assert(all.forall(o => o._2 >= 0 && o._2 < (1 << DebugRegMap.DBG_AW)),
      "an offset escapes the 20-bit dbg_axi window")
    val byOffset = all.groupBy(_._2).filter(_._2.size > 1)
    assert(byOffset.isEmpty, s"duplicate offsets: ${byOffset.map { case (o, ns) =>
      f"0x$o%05X -> ${ns.map(_._1).mkString("/")}" }.mkString(", ")}")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git stash push -- src/main/scala/m68k040/debug/DebugRegMap.scala
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -20
git stash pop
```

Expected: compilation fails with `not found: value DebugRegMap` — proving the spec genuinely depends on the generated object rather than restating it.

- [ ] **Step 3: Prove the drift check bites**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sed -i 's/  val OFF_MON_SENSE: Int = 0x0005C/  val OFF_MON_SENSE: Int = 0x0005C  \/\/ tampered below/' src/main/scala/m68k040/debug/DebugRegMap.scala
sed -i 's/("OFF_MON_SENSE", 0x0005C),/("OFF_MON_SENSE", 0x00064),/' src/main/scala/m68k040/debug/DebugRegMap.scala
~/sbt/bin/sbt "testOnly m68k040.debug.DebugRegMapSpec" 2>&1 | tail -20
git checkout -- src/main/scala/m68k040/debug/DebugRegMap.scala
```

Expected: `offset drift: OFF_MON_SENSE: def 0x0005C vs scala 0x00064` and a failed suite, then a clean checkout restoring the generated file.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugRegMapSpec"
```

Expected: `8 tests passed` (all tests in `DebugRegMapSpec`), `[success]`.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/test/scala/m68k040/debug/DebugRegMapSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage0): fastTest guard that the generated Scala map matches the definition

DebugRegMapSpec re-parses tools/debug/debug_regmap.def inside the JVM (no
interpreter assumption) and proves the generated DebugRegMap object still
matches it offset-for-offset, feature-for-feature, port-for-port and
constant-for-constant. Also pins alignment, uniqueness, the 20-bit window,
the deployed addresses of the block Stage 1 actually decodes, and that
featuresForStage is monotonic and honest at stage 1 (0x0004000F).

Runs untagged, so `make test-fast` fails the moment a generated file goes
stale -- the drift cannot reach synthesis.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Host-side protocol pinning (adapted from the sibling fake-REPL)

**Files:**
- Create: `tools/debug/dbg_protocol.py`
- Create: `tools/debug/test_dbg_protocol.py`
- Create: `tools/debug/run_tests.sh`
- Test: `tools/debug/test_dbg_protocol.py`, driven by `tools/debug/run_tests.sh`

**Interfaces:**
- Consumes: `tools/debug/debug_regmap.py` (generated in Task 4) for `OFF_*`, `FEATURES`, `features_for_stage`.
- Produces `tools/debug/dbg_protocol.py`, whose exact API Stage 1's Scala tests mirror and future stages reuse:
  - `class ProtocolError(Exception)`
  - `RESP_OKAY = 0`, `RESP_SLVERR = 2`
  - `CTRL_HALT = 1 << 0`, `CTRL_STEP = 1 << 1`, `CTRL_SOFT_RST = 1 << 2`, `CTRL_INIT_DONE_OVR = 1 << 3`, `CTRL_COLD_HOLD = 1 << 4`, `CTRL_COLD_PULSE = 1 << 5`, `CTRL_STEP_ARM = 1 << 7`
  - `STAT_HALTED = 1 << 0`, `STAT_EXC_PENDING = 1 << 1`, `STAT_INIT_DONE = 1 << 2`, `STAT_RUNNING = 1 << 3`, `STAT_AUTO_HALT = 1 << 4`
  - `ARCH_BUSY = 1 << 0`, `ARCH_DONE = 1 << 1`, `ARCH_REJECTED = 1 << 2`
  - `CACHE_BUSY = 1 << 0`, `CACHE_DONE = 1 << 1`, `CACHE_SEL_MASK = 0b1100`
  - `def control_word(halt=False, step=False, soft_rst=False, init_done_override=False, cold_hold=False, cold_pulse=False, step_arm=False) -> int`
  - `def merge_strobes(old, new, strb) -> int`
  - `def expect_read(stage, offset, implemented=None) -> int | None` — `0` for every offset whose stage exceeds `stage` or that is unmapped, `None` for "value depends on state, no constant expectation"
  - `def assert_feature_honesty(stage, features_word) -> None`
  - `def classify_control_writes(writes) -> list[str]`
  - `def arch_apply_poll(samples) -> str`
  - `def cache_op_poll(samples) -> str`

- [ ] **Step 1: Write the failing test**

Create `tools/debug/test_dbg_protocol.py`:

```python
#!/usr/bin/env python3
"""Host-protocol pinning for the CPU-side dbg_axi slave.

ADAPTED FROM the sibling's offline REPL model,
macqd700-soc/tb/tests/host/fake_jtag_repl.py, which reproduces
rtl/core/debug/debug_ctrl.v "offset-by-offset with its real bit layouts and its
real, sometimes deliberately awkward, quirks". That file is a 1722-line model of
the LEGACY controller including defects this core deliberately fixes (ARCH_APPLY
auto-resuming, BREAK_PC_CTRL's clear-bit-14-vs-read-bit-15 mismatch,
HALT_AFTER storing a delta rather than an absolute target). Copying it wholesale
would import those defects as requirements, so what is imported here is the part
that IS the frozen contract:

  * unmapped/absent offsets read zero and drop writes with an OKAY response;
  * WSTRB is honoured per byte for ordinary RW fields;
  * a feature bit is 1 only when every operation it implies is real;
  * the deployed Tcl step sequence HALT, HALT|STEP, 0 is ONE step command and
    the trailing zero must not cancel it;
  * cache-operation polling never sees a rejected command as BUSY=0/DONE=0;
  * arch-apply polling never sees REJECTED encoded as DONE.

Standard library only. Run: python3 tools/debug/test_dbg_protocol.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dbg_protocol as p       # noqa: E402
import debug_regmap as m       # noqa: E402

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def raises(label, exc, fn, *args):
    try:
        fn(*args)
    except exc:
        print("PASS  " + label)
        return
    except Exception as other:  # noqa: BLE001
        FAILURES.append(label)
        print("FAIL  " + label + "  -- raised %r, wanted %s" % (other, exc.__name__))
        return
    FAILURES.append(label)
    print("FAIL  " + label + "  -- did not raise %s" % exc.__name__)


def main():
    # ---- unmapped / absent offsets read zero -----------------------------------
    check("an offset outside the frozen map reads 0",
          p.expect_read(1, 0x00FFC) == 0)
    check("a reserved-forever offset reads 0 at every stage",
          p.expect_read(7, m.OFF_WEDGE0) == 0)
    check("a Stage-3 offset reads 0 in a Stage-1 build",
          p.expect_read(1, m.OFF_ARCH_D0) == 0)
    check("a Stage-1 offset has no constant expectation (it is stateful)",
          p.expect_read(1, m.OFF_STATUS) is None)
    check("CAP_TRACE reads 0 in a Stage-1 build (trace is Stage 7)",
          p.expect_read(1, m.OFF_CAP_TRACE) == 0)
    check("an out-of-window address is rejected outright",
          p.expect_read(1, 1 << 20) == 0)

    # ---- byte strobes ----------------------------------------------------------
    check("WSTRB=0xF replaces the whole word",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0xF) == 0x11223344)
    check("WSTRB=0x0 changes nothing",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x0) == 0xAABBCCDD)
    check("WSTRB=0x1 writes only byte 0",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x1) == 0xAABBCC44)
    check("WSTRB=0x8 writes only byte 3",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x8) == 0x11BBCCDD)
    check("WSTRB=0x6 writes the two middle bytes",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x6) == 0xAA2233DD)
    raises("an out-of-range strobe is a protocol error", p.ProtocolError,
           p.merge_strobes, 0, 0, 0x10)

    # ---- feature-bit honesty ---------------------------------------------------
    p.assert_feature_honesty(1, m.features_for_stage(1))
    print("PASS  a Stage-1 build may advertise exactly features_for_stage(1)")
    check("features_for_stage(1) is 0x0004000F",
          m.features_for_stage(1) == 0x0004000F,
          "got 0x%08X" % m.features_for_stage(1))
    raises("advertising the watchpoint bit from a Stage-1 build is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 1,
           m.features_for_stage(1) | (1 << m.FEATURES["watchpoints"][0]))
    raises("advertising the dcache_probe bit from a Stage-1 build is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 1,
           m.features_for_stage(1) | (1 << m.FEATURES["dcache_probe"][0]))
    raises("an undefined feature bit is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 7, 1 << 31)

    # ---- the deployed Tcl step sequence ---------------------------------------
    # jtag_repl.tcl writes HALT, then HALT|STEP, then zero. Spec 3.3: "The
    # controller must treat this as one step command. The final zero may release
    # an ordinary halt but must not cancel the step already accepted."
    seq = [p.control_word(halt=True),
           p.control_word(halt=True, step=True),
           0]
    check("the deployed 3-write step sequence is HALT, STEP, RESUME",
          p.classify_control_writes(seq) == ["HALT", "STEP", "RESUME"],
          repr(p.classify_control_writes(seq)))
    check("the sequence contains exactly one STEP",
          p.classify_control_writes(seq).count("STEP") == 1)
    check("a bare halt is HALT only",
          p.classify_control_writes([p.control_word(halt=True)]) == ["HALT"])
    check("a bare zero is RESUME only",
          p.classify_control_writes([0]) == ["RESUME"])
    check("STEP without HALT is still one STEP",
          p.classify_control_writes([p.control_word(step=True)]) == ["STEP"])
    check("cold-reset pulse is classified separately from halt/step",
          p.classify_control_writes([p.control_word(cold_pulse=True)]) == ["COLD_RESET_PULSE"])
    check("the deprecated bit-2 alias classifies as a cold-reset pulse",
          p.classify_control_writes([p.control_word(soft_rst=True)]) == ["COLD_RESET_PULSE"])
    check("cold-reset HOLD is a level, not a pulse",
          p.classify_control_writes([p.control_word(cold_hold=True)])
          == ["COLD_RESET_HOLD_SET", "RESUME"])

    # ---- arch-apply polling ----------------------------------------------------
    check("BUSY then DONE polls to DONE",
          p.arch_apply_poll([p.ARCH_BUSY, p.ARCH_BUSY, p.ARCH_DONE]) == "DONE")
    check("BUSY then REJECTED polls to REJECTED",
          p.arch_apply_poll([p.ARCH_BUSY, p.ARCH_REJECTED]) == "REJECTED")
    check("an immediate REJECTED (no BUSY) is legal",
          p.arch_apply_poll([p.ARCH_REJECTED]) == "REJECTED")
    raises("REJECTED must never be encoded as DONE too", p.ProtocolError,
           p.arch_apply_poll, [p.ARCH_BUSY, p.ARCH_DONE | p.ARCH_REJECTED])
    raises("going idle without DONE or REJECTED is a stuck-status bug",
           p.ProtocolError, p.arch_apply_poll, [p.ARCH_BUSY, 0])

    # ---- cache-operation polling ----------------------------------------------
    check("BUSY then DONE polls to DONE",
          p.cache_op_poll([p.CACHE_BUSY, p.CACHE_BUSY, p.CACHE_DONE]) == "DONE")
    check("the selected-cache field is preserved alongside DONE",
          p.cache_op_poll([p.CACHE_BUSY, p.CACHE_DONE | 0b0100]) == "DONE")
    raises("a command that goes idle with neither BUSY nor DONE is rejected-in-silence",
           p.ProtocolError, p.cache_op_poll, [p.CACHE_BUSY, 0])
    raises("BUSY and DONE together is an illegal encoding", p.ProtocolError,
           p.cache_op_poll, [p.CACHE_BUSY | p.CACHE_DONE])
    raises("polling an empty sample list is a harness error", p.ProtocolError,
           p.cache_op_poll, [])

    # ---- CONTROL / STATUS bit layout (spec 3.3) --------------------------------
    check("CONTROL bit numbering matches spec 3.3",
          (p.CTRL_HALT, p.CTRL_STEP, p.CTRL_SOFT_RST, p.CTRL_INIT_DONE_OVR,
           p.CTRL_COLD_HOLD, p.CTRL_COLD_PULSE, p.CTRL_STEP_ARM)
          == (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x80))
    check("STATUS bit numbering matches spec 3.3",
          (p.STAT_HALTED, p.STAT_EXC_PENDING, p.STAT_INIT_DONE,
           p.STAT_RUNNING, p.STAT_AUTO_HALT) == (0x01, 0x02, 0x04, 0x08, 0x10))
    check("CONTROL bit 6 is reserved and never set by control_word",
          all((p.control_word(**{k: True}) & 0x40) == 0
              for k in ("halt", "step", "soft_rst", "init_done_override",
                        "cold_hold", "cold_pulse", "step_arm")))

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
python3 tools/debug/test_dbg_protocol.py; echo "exit=$?"
```

Expected: `ModuleNotFoundError: No module named 'dbg_protocol'`, `exit=1`.

- [ ] **Step 3: Write the protocol module**

Create `tools/debug/dbg_protocol.py`:

```python
#!/usr/bin/env python3
"""Frozen host-side protocol rules for the CPU-side dbg_axi debug/control slave.

These are the rules a host tool (jtag_repl.tcl, the Python GDB bridge) and the
RTL must BOTH obey, expressed once so both sides can be tested against the same
statements. Nothing here models a legacy defect: where the deployed controller
and this core's design spec disagree, the SPEC wins, and the divergence is named
in a comment.

Standard library only. Import as `dbg_protocol`.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import debug_regmap as _m
import regmap as _regmap


class ProtocolError(Exception):
    """A sequence or encoding the contract forbids."""


# AXI response codes. The slave answers OKAY for everything, including unmapped
# offsets -- spec 3.1: "return OKAY/zero for unmapped reads and OKAY/drop for
# unmapped writes".
RESP_OKAY = 0
RESP_SLVERR = 2

# OFF_CONTROL write encoding, spec 3.3.
CTRL_HALT = 1 << 0            # manual halt request LEVEL
CTRL_STEP = 1 << 1            # single-step PULSE
CTRL_SOFT_RST = 1 << 2        # deprecated reset-pulse alias
CTRL_INIT_DONE_OVR = 1 << 3   # init-done override level, SoC-owned
CTRL_COLD_HOLD = 1 << 4       # cold-reset hold level, survives CPU reset
CTRL_COLD_PULSE = 1 << 5      # cold-reset pulse
CTRL_RESERVED6 = 1 << 6       # reserved, reads zero
CTRL_STEP_ARM = 1 << 7        # legacy step-arm observation

# OFF_STATUS read encoding, spec 3.3.
STAT_HALTED = 1 << 0
STAT_EXC_PENDING = 1 << 1
STAT_INIT_DONE = 1 << 2
STAT_RUNNING = 1 << 3
STAT_AUTO_HALT = 1 << 4

# OFF_ARCH_STATUS, spec 7.3: "retains bits BUSY=0, DONE=1, REJECTED=2".
ARCH_BUSY = 1 << 0
ARCH_DONE = 1 << 1
ARCH_REJECTED = 1 << 2

# OFF_DCACHE_OP / OFF_ICACHE_OP status, spec 8.2: "read bit 0 BUSY, bit 1 DONE,
# bits 3:2 selected cache(s)". The append-only location of REJECTED/ERROR is
# spec section 14 item 4 and is STILL OPEN, so nothing here invents a bit for
# them; the rule pinned instead is the one spec 8.2 states outright -- a rejected
# command "must not silently leave BUSY=0/DONE=0".
CACHE_BUSY = 1 << 0
CACHE_DONE = 1 << 1
CACHE_SEL_MASK = 0b1100

_RM = _regmap.load()


def _stage_of(offset):
    """The stage that makes `offset` real, or None when it is not in the map."""
    for reg in _RM.regs:
        if reg.offset == offset:
            return reg.stage
    for blk in _RM.blks:
        for _name, off in blk.elements():
            if off == offset:
                return blk.stage
    for rng in _RM.ranges:
        if rng.base <= offset < rng.base + rng.size:
            return rng.stage
    return None


def expect_read(stage, offset):
    """Expected read value for `offset` in a build of the given `stage`.

    Returns 0 for every unmapped, out-of-window, reserved-forever, or
    not-yet-implemented offset (spec 3.1/3.2). Returns the constant for the two
    offsets whose value is fixed by the contract itself. Returns None when the
    offset IS implemented at this stage but its value depends on live state, so
    the caller must assert something more specific."""
    if offset < 0 or offset >= (1 << _m.DBG_AW) or offset % 4 != 0:
        return 0
    item_stage = _stage_of(offset)
    if item_stage is None or item_stage > stage:
        return 0
    if offset == _m.OFF_VERSION:
        return _m.VERSION_VALUE
    if offset == _m.OFF_FEATURES:
        return _m.features_for_stage(stage)
    return None


def merge_strobes(old, new, strb):
    """Apply AXI byte strobes: per spec 3.1, "honor WSTRB per byte for ordinary
    RW fields". Bytes whose strobe is 0 keep their old value."""
    if strb < 0 or strb > 0xF:
        raise ProtocolError("WSTRB 0x%X is outside 0x0..0xF" % strb)
    out = 0
    for i in range(4):
        byte = (new if (strb >> i) & 1 else old) >> (8 * i)
        out |= (byte & 0xFF) << (8 * i)
    return out


def assert_feature_honesty(stage, features_word):
    """Raise unless every bit set in `features_word` is a defined feature whose
    implementing stage is <= `stage`.

    Spec 3.4: "No feature bit may advertise a tied-off counter, stale shadow,
    placeholder probe, or operation that can be silently dropped." """
    by_bit = {bit: (name, feat_stage) for name, (bit, feat_stage) in _m.FEATURES.items()}
    for bit in range(32):
        if not ((features_word >> bit) & 1):
            continue
        if bit not in by_bit:
            raise ProtocolError("OFF_FEATURES bit %d is not a defined feature" % bit)
        name, feat_stage = by_bit[bit]
        if feat_stage > stage:
            raise ProtocolError(
                "OFF_FEATURES advertises '%s' (bit %d, needs stage %d) from a stage-%d build"
                % (name, bit, feat_stage, stage))


def classify_control_writes(writes):
    """Turn a list of OFF_CONTROL write words into the semantic commands they mean.

    The deployed Tcl `step` writes HALT, then HALT|STEP, then zero. Spec 3.3:
    "The controller must treat this as one step command. The final zero may
    release an ordinary halt but must not cancel the step already accepted." A
    STEP is therefore emitted for the middle write and the trailing zero is a
    plain RESUME -- it never emits a STEP_CANCEL."""
    out = []
    for word in writes:
        if word < 0 or word > 0xFFFFFFFF:
            raise ProtocolError("CONTROL word 0x%X is not 32 bits" % word)
        if word & CTRL_RESERVED6:
            raise ProtocolError("CONTROL bit 6 is reserved and must be written 0")
        emitted = False
        if word & (CTRL_COLD_PULSE | CTRL_SOFT_RST):
            out.append("COLD_RESET_PULSE")
            emitted = True
        if word & CTRL_COLD_HOLD:
            out.append("COLD_RESET_HOLD_SET")
            emitted = True
        if word & CTRL_INIT_DONE_OVR:
            out.append("INIT_DONE_OVERRIDE_SET")
            emitted = True
        if word & CTRL_STEP:
            out.append("STEP")
        elif word & CTRL_HALT:
            out.append("HALT")
        elif not (word & (CTRL_COLD_PULSE | CTRL_SOFT_RST)):
            # An accepted CONTROL write with bit 0 clear IS an explicit resume
            # request (spec 3.3 bit 0, spec 6.4) -- including the trailing zero
            # of the step sequence, which resumes but does not cancel the step.
            out.append("RESUME")
        elif not emitted:
            out.append("RESUME")
    return out


def arch_apply_poll(samples):
    """Reduce a sequence of OFF_ARCH_STATUS reads to DONE or REJECTED.

    Spec 7.3: "DONE means all writes and required invalidations completed.
    REJECTED is never encoded as DONE." """
    if not samples:
        raise ProtocolError("no OFF_ARCH_STATUS samples")
    for word in samples:
        if (word & ARCH_DONE) and (word & ARCH_REJECTED):
            raise ProtocolError("OFF_ARCH_STATUS 0x%X encodes DONE and REJECTED together" % word)
        if word & ARCH_REJECTED:
            return "REJECTED"
        if word & ARCH_DONE:
            return "DONE"
        if not (word & ARCH_BUSY):
            raise ProtocolError(
                "OFF_ARCH_STATUS went idle (0x%X) without DONE or REJECTED" % word)
    raise ProtocolError("OFF_ARCH_STATUS never left BUSY")


def cache_op_poll(samples):
    """Reduce a sequence of OFF_DCACHE_OP/OFF_ICACHE_OP reads to DONE.

    Spec 8.2: a rejected command "must not silently leave BUSY=0/DONE=0", and
    "DONE is set only after the real walk and all writeback responses finish"."""
    if not samples:
        raise ProtocolError("no cache-op status samples")
    for word in samples:
        if (word & CACHE_BUSY) and (word & CACHE_DONE):
            raise ProtocolError("cache status 0x%X asserts BUSY and DONE together" % word)
        if word & CACHE_DONE:
            return "DONE"
        if not (word & CACHE_BUSY):
            raise ProtocolError(
                "cache status went idle (0x%X) with neither BUSY nor DONE -- a rejected "
                "command must be reported, not left silent" % word)
    raise ProtocolError("cache operation never left BUSY")


def control_word(halt=False, step=False, soft_rst=False, init_done_override=False,
                 cold_hold=False, cold_pulse=False, step_arm=False):
    """Build an OFF_CONTROL write word from named intents (spec 3.3)."""
    word = 0
    if halt:
        word |= CTRL_HALT
    if step:
        word |= CTRL_STEP
    if soft_rst:
        word |= CTRL_SOFT_RST
    if init_done_override:
        word |= CTRL_INIT_DONE_OVR
    if cold_hold:
        word |= CTRL_COLD_HOLD
    if cold_pulse:
        word |= CTRL_COLD_PULSE
    if step_arm:
        word |= CTRL_STEP_ARM
    return word
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && python3 tools/debug/test_dbg_protocol.py
```

Expected: all `PASS` lines and `All checks passed.`, exit 0. Note the test's `CAP_TRACE reads 0 in a Stage-1 build` case relies on `OFF_CAP_TRACE` having `STAGE 7` in the `.def`, so it returns `0` from the stage comparison before reaching the stateful/constant branches.

- [ ] **Step 5: Add the runner and commit**

Create `tools/debug/run_tests.sh`:

```bash
#!/usr/bin/env bash
# Run every host-side debug-contract check. Exit 0 only if all pass.
# The sibling cross-conformance check exits 77 when macqd700-soc is absent;
# that is reported as a SKIP and does not mask a real failure.
set -uo pipefail
cd "$(dirname "$0")/../.."

rc=0
for t in tools/debug/test_debug_regmap.py \
         tools/debug/test_dbg_protocol.py; do
  echo "=== $t ==="
  python3 "$t" || rc=1
done

echo "=== tools/debug/gen_debug_regmap.py --check ==="
python3 tools/debug/gen_debug_regmap.py --check || rc=1

echo "=== tools/debug/test_sibling_conformance.py ==="
python3 tools/debug/test_sibling_conformance.py
case $? in
  0)  ;;
  77) echo "SKIP: macqd700-soc checkout absent (set MACQD700_SOC)" ;;
  *)  rc=1 ;;
esac

exit $rc
```

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
chmod +x tools/debug/run_tests.sh
./tools/debug/run_tests.sh; echo "exit=$?"
git add tools/debug/dbg_protocol.py tools/debug/test_dbg_protocol.py tools/debug/run_tests.sh
git commit -m "$(cat <<'EOF'
debug(stage0): pin the frozen host protocol, adapted from the sibling fake-REPL

macqd700-soc/tb/tests/host/fake_jtag_repl.py models the LEGACY debug_ctrl
offset-by-offset INCLUDING defects this core deliberately fixes (ARCH_APPLY
auto-resuming, BREAK_PC_CTRL clear-bit-14-vs-read-bit-15, HALT_AFTER storing a
delta not an absolute target). Importing it wholesale would import those
defects as requirements, so what is imported is the part that IS the frozen
contract, restated as executable rules both the host tools and the RTL tests
can be checked against:

  * unmapped/absent/reserved offsets read zero, writes drop with OKAY;
  * WSTRB is honoured per byte;
  * a feature bit is 1 only when the implementing stage has built it;
  * the deployed Tcl step sequence HALT, HALT|STEP, 0 is ONE step and the
    trailing zero resumes without cancelling it;
  * cache-op polling never accepts a silently-dropped command;
  * arch-apply polling never accepts REJECTED encoded as DONE.

Deliberately does NOT invent bit positions for cache REJECTED/ERROR -- spec
section 14 item 4 is still open and blocks Stage 4, not Stage 0/1.

tools/debug/run_tests.sh runs the whole host-side set, treating an absent
sibling checkout as SKIP (exit 77) rather than a pass.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

Expected from the runner: every sub-script prints `All checks passed.`, three `CURRENT:` lines from the generator check, and `exit=0`.

---

## Task 7: `DbgAxiLite` bundle + `DebugCtrlPlugin` AXI/reset shell

**Files:**
- Create: `src/main/scala/m68k040/debug/DbgAxiLite.scala`
- Create: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Create: `src/test/scala/m68k040/debug/DebugCtrlDut.scala`
- Test: `src/test/scala/m68k040/debug/DebugCtrlAxiSpec.scala`

**Interfaces:**
- Consumes: `m68k040.debug.DebugRegMap.{DBG_AW, DBG_DW, POR_CYCLES_DEFAULT}` (Task 4).
- Produces, relied on verbatim by Tasks 8-13:
  - `case class DbgAxiLite(addressWidth: Int = 20, dataWidth: Int = 32) extends Bundle with IMasterSlave` with fields `awaddr: UInt`, `awvalid/awready: Bool`, `wdata: Bits`, `wstrb: Bits`, `wvalid/wready: Bool`, `bresp: Bits`, `bvalid: Bool`, `bready: Bool`, `araddr: UInt`, `arvalid/arready: Bool`, `rdata: Bits`, `rresp: Bits`, `rvalid: Bool`, `rready: Bool`.
  - `class DebugCtrlPlugin(val buildId: BigInt = 0, val porCycles: Int = DebugRegMap.POR_CYCLES_DEFAULT, val stage: Int = 1) extends FiberPlugin`, whose `logic` Area exposes `dbgAxi: DbgAxiLite` (named `dbg_axi` in Verilog), `dbgRst: Bool`, and a `csr` sub-`Area` holding `awPend/awAddr/wPend/wData/wStrb/bPend/arPend/arAddr/rPend/rData` plus the `doWrite: Bool` / `doRead: Bool` strobes and the `merged(cur: Bits): Bits` byte-strobe helper.
  - `class DebugCtrlDut(buildIdArg: BigInt = 0x12345678L, porCyclesArg: Int = 4, stageArg: Int = 1) extends Component` with field `dbg: DebugCtrlPlugin` and `def axi: DbgAxiLite = dbg.logic.dbgAxi`.
  - `object DbgAxiDriver` with `def idle(b: DbgAxiLite): Unit`, `def write(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long, strb: Int = 0xF): Int`, `def writeAwFirst(b, cd, addr, data, strb, gap: Int): Int`, `def writeWFirst(b, cd, addr, data, strb, gap: Int): Int`, `def read(b: DbgAxiLite, cd: ClockDomain, addr: Long): Long`.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/DebugCtrlAxiSpec.scala`:

```scala
package m68k040.debug

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Stage 1, spec section 12: "Unit-test independent AW/W order, backpressure, byte
  * strobes, reset during idle, CPU reset during accepted transactions, and READY-low
  * during debug reset." This suite covers the pure-AXI half; the CSR-value half lives
  * in DebugCtrlCsrSpec and the reset half in DebugCtrlResetSpec. */
class DebugCtrlAxiSpec extends AnyFunSuite {

  test("READY is low while the debug power-on reset is asserted") {
    M68kSim().compile(new DebugCtrlDut(porCyclesArg = 8)).doSim { dut =>
      val b = dut.axi
      DbgAxiDriver.idle(b)
      // Present a read request from the very first cycle: an unconditionally
      // combinational arready (the legacy defect debug_reset_ctl.v's header
      // describes) would accept it and then never answer.
      b.arvalid #= true
      b.araddr  #= 0
      dut.clockDomain.forkStimulus(10)
      for (i <- 0 until 4) {
        dut.clockDomain.waitSampling()
        assert(!b.arready.toBoolean, s"arready asserted during debug reset (cycle $i)")
        assert(!b.awready.toBoolean, s"awready asserted during debug reset (cycle $i)")
        assert(!b.wready.toBoolean,  s"wready asserted during debug reset (cycle $i)")
      }
      // ... and once POR completes the very same request is served.
      var guard = 0
      while (!b.arready.toBoolean && guard < 64) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 64, "arready never asserted after the debug POR window")
      dut.clockDomain.waitSamplingWhere(b.rvalid.toBoolean)
      assert(b.rresp.toInt == 0, "unmapped read must answer OKAY")
    }
  }

  test("a write with AW and W presented together completes with OKAY") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.write(dut.axi, dut.clockDomain, 0x00058, 0x00000018L)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("AW may arrive well before W") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.writeAwFirst(dut.axi, dut.clockDomain, 0x00058, 0x00000018L, 0xF, 7)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("W may arrive well before AW") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.writeWFirst(dut.axi, dut.clockDomain, 0x00058, 0x00000018L, 0xF, 7)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("BVALID and BRESP hold stable while BREADY is low") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(b)
      b.bready #= false
      dut.clockDomain.waitSampling(20)
      b.awaddr #= 0x00058; b.awvalid #= true
      b.wdata  #= 0x18;    b.wstrb   #= 0xF; b.wvalid #= true
      dut.clockDomain.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
      b.awvalid #= false; b.wvalid #= false
      dut.clockDomain.waitSamplingWhere(b.bvalid.toBoolean)
      for (i <- 0 until 12) {
        dut.clockDomain.waitSampling()
        assert(b.bvalid.toBoolean, s"BVALID dropped before BREADY at cycle $i")
        assert(b.bresp.toInt == 0, s"BRESP changed while held at cycle $i")
        // A second write must NOT be accepted while the first response is outstanding.
        assert(!b.awready.toBoolean, s"awready high with an outstanding B response ($i)")
      }
      b.bready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.bvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "BVALID never cleared after BREADY")
    }
  }

  test("RVALID and RDATA hold stable while RREADY is low") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(b)
      b.rready #= false
      dut.clockDomain.waitSampling(20)
      b.araddr #= 0x00FFC; b.arvalid #= true
      dut.clockDomain.waitSamplingWhere(b.arready.toBoolean)
      b.arvalid #= false
      dut.clockDomain.waitSamplingWhere(b.rvalid.toBoolean)
      val first = b.rdata.toLong
      for (i <- 0 until 12) {
        dut.clockDomain.waitSampling()
        assert(b.rvalid.toBoolean, s"RVALID dropped before RREADY at cycle $i")
        assert(b.rdata.toLong == first, s"RDATA changed while held at cycle $i")
        assert(!b.arready.toBoolean, s"arready high with an outstanding R response ($i)")
      }
      b.rready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.rvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "RVALID never cleared after RREADY")
    }
  }

  test("unmapped offsets read zero and drop writes, both with OKAY") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      // A hole inside the frozen namespace, a reserved-forever offset, and an
      // offset belonging to a later stage must all read 0 (spec 3.1/3.2).
      for (off <- Seq(0x00FFCL, 0x00024L, DebugRegMap.OFF_WEDGE0.toLong,
                      DebugRegMap.OFF_ARCH_D0.toLong, DebugRegMap.OFF_CAP_TRACE.toLong,
                      0x0FFFCL)) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain, off, 0xDEADBEEFL) == 0,
          f"write to 0x$off%05X must answer OKAY")
        val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, off)
        assert(got == 0L, f"read of 0x$off%05X must be zero, got 0x$got%08X")
      }
    }
  }

  test("back-to-back transactions do not wedge the slave") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      for (i <- 0 until 24) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain, 0x00FF0, i.toLong) == 0)
        assert(DbgAxiDriver.read(dut.axi, dut.clockDomain, 0x00FF0) == 0L)
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlAxiSpec" 2>&1 | tail -20
```

Expected: compilation error — `not found: type DebugCtrlDut`, `not found: value DbgAxiDriver`.

- [ ] **Step 3a: Write the bundle**

Create `src/main/scala/m68k040/debug/DbgAxiLite.scala`:

```scala
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
```

- [ ] **Step 3b: Write the plugin shell**

Create `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`:

```scala
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
  *  - '''CPU runtime''' -- state that must restart with the CPU. Stage 1 has none; the
  *    CPU reset is only OBSERVED, as a rising edge detected inside the debug domain.
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

      // ── Read mux ──────────────────────────────────────────────────────────────────
      // Stage 1's CSR values are added by Tasks 8-10. The default arm is the whole
      // contract for every offset this stage does not implement: spec 3.2 -- "It must
      // reserve them, return zero for absent functions, and never repurpose them."
      when(doRead) {
        rData := B(0, DebugRegMap.DBG_DW bits)
      }

      // ── Write decode ──────────────────────────────────────────────────────────────
      // Writes to anything this stage does not implement are dropped with an OKAY
      // response, which the FSM above already produces unconditionally.
      when(doWrite) {
        // Register writes are added by Tasks 9-10.
      }

      // ── Required assertion (spec section 13) ──────────────────────────────────────
      GenerationFlags.simulation {
        assert(!(dbgRst && (dbgAxi.awready || dbgAxi.wready || dbgAxi.arready)),
          "DebugCtrlPlugin: AXI READY asserted while the debug domain is in reset " +
          "(spec section 13: 'debug reset cannot accept AXI requests')",
          FAILURE)
      }
    }
  }
}
```

- [ ] **Step 3c: Write the test DUT and driver**

Create `src/test/scala/m68k040/debug/DebugCtrlDut.scala`:

```scala
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
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlAxiSpec"
```

Expected: 8 tests pass, `[success]`. If SpinalHDL raises a clock-domain-crossing error on `coreCd.isResetActive` or on the `porCd`/`dbgCd` reads, the `setSynchronousWith(coreCd)` calls above are the fix and are already present; if it instead objects that `dbgAxi` cannot be renamed, use `dbgAxi.setName("dbg_axi", weak = false)`.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/DbgAxiLite.scala \
        src/main/scala/m68k040/debug/DebugCtrlPlugin.scala \
        src/test/scala/m68k040/debug/DebugCtrlDut.scala \
        src/test/scala/m68k040/debug/DebugCtrlAxiSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage1): DebugCtrlPlugin AXI4-Lite shell with its own surviving reset domain

First RTL of the debug/control slave. DbgAxiLite carries EXACTLY the socket's
signal set (cpu_socket.vh:145-162) -- no AxPROT, because the socket declares
none, which is also why SpinalHDL's own AxiLite4/AxiLite4SlaveFactory are not
used; the second reason is that READY must be structurally low during debug
reset, and the factory owns its own READY.

The plugin runs its registers in a derived ClockDomain whose reset is a
self-generated power-on pulse built from resetKind=BOOT (reset-less) flops --
the SpinalHDL translation of macqd700-soc's debug_reset_ctl.v, whose header
documents the real 2026-07-26 hardware bug this shape exists to prevent: debug
config wiped on every CPU reset, and debug reads swallowed while cpu_rst was
held (the ~10 s Xicom timeout / 0xBADA0BAD sentinel).

AW and W are captured into independent skid registers and the write applies
only once both arrive; at most one outstanding read and one outstanding write;
the read mux result is registered (spec 11 rule 1); every offset this stage
does not implement reads zero and drops writes with OKAY.

No Global key and no service trait added (spec 0.9; Stage 2's DebugCommitService
is the first real cross-plugin seam).

Tests: READY low through the whole POR window then the same request served,
AW-before-W, W-before-AW, B/R backpressure with payload held and no second
transaction accepted, unmapped/reserved/later-stage offsets reading zero, and
24 back-to-back transactions without wedging.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Identity and capability registers

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala` (the `when(doRead)` arm added in Task 7)
- Test: `src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala`

**Interfaces:**
- Consumes: `DebugCtrlPlugin.logic.csr.{doRead, arAddr, rData}` and the constructor fields `buildId`/`stage` (Task 7); `DebugRegMap.{OFF_VERSION, OFF_BUILD_ID, OFF_FEATURES, OFF_CAP_TRACE, VERSION_VALUE, featuresForStage}` (Task 4); `DebugCtrlDut`/`DbgAxiDriver` (Task 7).
- Produces: `OFF_VERSION`, `OFF_BUILD_ID` and `OFF_FEATURES` as live read arms in the `switch(arAddr)` block, which Tasks 9-11 extend with further `is(...)` arms.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala`:

```scala
package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Stage 1 CSR values: identity, capability, CONTROL/STATUS and the SoC-fabric
  * configuration registers. Every expectation is derived from `DebugRegMap` (which is
  * generated from `tools/debug/debug_regmap.def`) rather than restated as a literal, so a
  * contract change cannot pass by being copied into the test too. */
class DebugCtrlCsrSpec extends AnyFunSuite {

  private val BUILD = 0xC0FFEE01L

  test("OFF_VERSION reads the version epoch") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_VERSION.toLong)
      assert(got == DebugRegMap.VERSION_VALUE.toLong,
        f"OFF_VERSION = 0x$got%08X, expected 0x${DebugRegMap.VERSION_VALUE}%08X")
      assert(got == 0xDEB60100L, "spec section 3.1 fixes the epoch at 0xDEB6_0100")
    }
  }

  test("OFF_BUILD_ID reads back the SoC-supplied constructor parameter") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_BUILD_ID.toLong)
      assert(got == BUILD, f"OFF_BUILD_ID = 0x$got%08X, expected 0x$BUILD%08X")
    }
  }

  test("OFF_BUILD_ID is read-only") {
    M68kSim().compile(new DebugCtrlDut(buildIdArg = BigInt(BUILD))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_BUILD_ID.toLong, 0xFFFFFFFFL) == 0, "write must answer OKAY")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_BUILD_ID.toLong) == BUILD, "OFF_BUILD_ID must not be writable")
    }
  }

  test("OFF_FEATURES advertises exactly what this stage implements") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_FEATURES.toLong)
      val want = DebugRegMap.featuresForStage(1).toLong
      assert(got == want, f"OFF_FEATURES = 0x$got%08X, expected 0x$want%08X")
      assert(got == 0x0004000FL, "spec section 15.5 fixes the Stage-1 value")
      // Every bit that is set must belong to a feature this stage really built.
      for ((name, bit, featStage) <- DebugRegMap.features if ((got >> bit) & 1L) == 1L)
        assert(featStage <= 1,
          s"OFF_FEATURES advertises '$name' (bit $bit, stage $featStage) from a stage-1 build")
      // And every optional bit must be clear (spec Stage 1: "Keep every optional feature
      // bit zero").
      for (optional <- Seq("pc_trace", "exc_ring", "break_pc_multi", "halt_exc_mask",
                           "live_arch", "dcache_probe", "perf_counters", "watchpoints",
                           "atrap_bp", "atrap_regcap", "atrap_d0qual",
                           "arch_apply_stays_halted", "arch_dirty_apply",
                           "cache_maint_only", "macro_retire_count", "stop_status_v2")) {
        val bit = DebugRegMap.features.find(_._1 == optional)
          .getOrElse(fail(s"feature '$optional' is missing from DebugRegMap"))._2
        assert(((got >> bit) & 1L) == 0L, s"optional feature '$optional' (bit $bit) must read 0")
      }
    }
  }

  test("OFF_FEATURES is read-only") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_FEATURES.toLong, 0xFFFFFFFFL) == 0)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_FEATURES.toLong) == DebugRegMap.featuresForStage(1).toLong)
    }
  }

  test("OFF_CAP_TRACE reads zero because Stage 1 has no trace memories") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CAP_TRACE.toLong) == 0L,
        "trace depths must be 0 while feature bits 4/5 are clear")
    }
  }

  test("a higher stage parameter advertises strictly more, never less") {
    // The plugin is stage-parameterised so a later tranche cannot forget to widen
    // OFF_FEATURES, and cannot widen it by hand-editing a literal.
    M68kSim().compile(new DebugCtrlDut(stageArg = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_FEATURES.toLong)
      val stage1 = DebugRegMap.featuresForStage(1).toLong
      assert(got == DebugRegMap.featuresForStage(2).toLong)
      assert((got & stage1) == stage1, "stage 2 must not retract a stage-1 bit")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlCsrSpec" 2>&1 | tail -25
```

Expected: the first six tests fail with `OFF_VERSION = 0x00000000, expected 0xDEB60100` and similar — Task 7's read mux returns zero for everything. (The `OFF_CAP_TRACE` test passes already; that is correct and intended, since Stage 1 implements it by returning zero.)

- [ ] **Step 3: Write minimal implementation**

In `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`, replace the Task-7 read arm:

```scala
      when(doRead) {
        rData := B(0, DebugRegMap.DBG_DW bits)
      }
```

with:

```scala
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
        }
      }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlCsrSpec m68k040.debug.DebugCtrlAxiSpec"
```

Expected: 7 + 8 = 15 tests pass, `[success]`. The `DebugCtrlAxiSpec` unmapped-offset test still passes because `OFF_CAP_TRACE`, `OFF_WEDGE0` and `OFF_ARCH_D0` remain unlisted in the switch.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala \
        src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage1): identity and capability registers

OFF_VERSION reads the spec 3.1 epoch 0xDEB6_0100, OFF_BUILD_ID reads the
SoC-supplied constructor parameter, and OFF_FEATURES reads
DebugRegMap.featuresForStage(stage) -- computed from the STAGE column of
tools/debug/debug_regmap.def, never a hand-written literal. That is spec 3.4's
"No feature bit may advertise a tied-off counter, stale shadow, placeholder
probe, or operation that can be silently dropped" expressed structurally: a
build that has not implemented a feature cannot advertise it without editing
the register map, which the Stage-0 conformance tests then reject.

OFF_CAP_TRACE is deliberately left out of the read switch so it returns the
reserved zero, matching feature bits 4/5 being clear.

The read switch has no default arm: an unlisted offset keeps the unconditional
zero, so adding a register can never un-reserve a neighbour.

Tests assert against DebugRegMap rather than restating literals, plus explicit
checks that all 16 optional feature bits read 0 and that a stage-2 build is a
strict superset of stage 1.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: CONTROL / STATUS and the cold-reset outputs

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala` (socket outputs, config registers, read/write arms)
- Modify: `src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala` (append the CONTROL/STATUS tests)
- Test: `src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala`

**Interfaces:**
- Consumes: `DebugCtrlPlugin.logic.csr.{doWrite, doRead, awAddr, arAddr, wData, wStrb, rData}` (Task 7); `DebugRegMap.{OFF_CONTROL, OFF_STATUS}` (Task 4).
- Produces, relied on by Tasks 10-12:
  - core-level ports `coldResetPulse: Bool` (`cpu_cold_reset_pulse`), `coldResetHold: Bool` (`cpu_cold_reset_hold`), `initDoneSeen: Bool` (`init_done_seen`);
  - debug-domain registers `csr.ctrlInitDoneOvr: Bool`, `csr.ctrlColdHold: Bool`, `csr.coldPulse: Bool`, `csr.initDoneSticky: Bool`, all `simPublic()`;
  - `csr.initDoneLatched: Bool` and `csr.controlWord: Bits` (a `def`, 32 bits), the single definition of the CONTROL readback word shared by the read mux and the write-side merge.

- [ ] **Step 1: Write the failing test**

Append to `src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala`, inside the class:

```scala
  // ── CONTROL / STATUS (spec sections 3.3 and 15.2) ───────────────────────────────

  private val CTRL_HALT           = 1L << 0
  private val CTRL_STEP           = 1L << 1
  private val CTRL_SOFT_RST       = 1L << 2
  private val CTRL_INIT_DONE_OVR  = 1L << 3
  private val CTRL_COLD_HOLD      = 1L << 4
  private val CTRL_COLD_PULSE     = 1L << 5
  private val CTRL_STEP_ARM       = 1L << 7

  private val STAT_HALTED       = 1L << 0
  private val STAT_EXC_PENDING  = 1L << 1
  private val STAT_INIT_DONE    = 1L << 2
  private val STAT_RUNNING      = 1L << 3
  private val STAT_AUTO_HALT    = 1L << 4

  test("CONTROL bit 4 (cold-reset hold) is a level that drives cpu_cold_reset_hold") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      assert(!dut.dbg.logic.coldResetHold.toBoolean, "cold-reset hold must be clear at POR")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_HOLD)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean, "cpu_cold_reset_hold must follow bit 4")
      val rb = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((rb & CTRL_COLD_HOLD) != 0, f"CONTROL readback 0x$rb%08X lost bit 4")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      dut.clockDomain.waitSampling(2)
      assert(!dut.dbg.logic.coldResetHold.toBoolean, "writing 0 must release the hold")
    }
  }

  test("CONTROL bit 5 emits a ONE-cycle cpu_cold_reset_pulse") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.coldResetPulse.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_PULSE)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"cpu_cold_reset_pulse was high for $high cycles, expected exactly 1")
    }
  }

  test("CONTROL bit 2 is the deprecated alias for the cold-reset pulse") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.coldResetPulse.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_SOFT_RST)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"the bit-2 alias produced $high pulse cycles, expected exactly 1")
    }
  }

  test("CONTROL halt/step bits are RAZ/WI in Stage 1 -- no pretending") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_HALT | CTRL_STEP | CTRL_STEP_ARM)
      val rb = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong)
      assert((rb & (CTRL_HALT | CTRL_STEP | CTRL_STEP_ARM)) == 0,
        f"CONTROL 0x$rb%08X must read 0 for halt/step in a Stage-1 build (spec 15.2)")
      assert((rb & 0x40L) == 0, "CONTROL bit 6 is reserved and reads 0")
      val st = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert((st & STAT_HALTED) == 0, "STATUS.halted must stay 0 -- there is no halt yet")
      assert((st & STAT_RUNNING) != 0, "STATUS.running must be 1 while not halted")
    }
  }

  test("STATUS halted and running are mutually exclusive") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      val st = DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert(((st & STAT_HALTED) != 0) != ((st & STAT_RUNNING) != 0),
        f"STATUS 0x$st%08X violates spec 3.3 mutual exclusion")
      assert((st & STAT_EXC_PENDING) == 0)
      assert((st & STAT_AUTO_HALT) == 0)
      assert((st & ~0x1FL) == 0, f"STATUS 0x$st%08X sets a bit above 4")
    }
  }

  test("init_done_seen latches sticky and CONTROL bit 3 overrides it") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      def status(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain, DebugRegMap.OFF_STATUS.toLong)
      assert((status() & STAT_INIT_DONE) == 0, "init-done must start clear")

      // The override alone sets the status bit.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong,
        CTRL_INIT_DONE_OVR)
      assert((status() & STAT_INIT_DONE) != 0, "CONTROL bit 3 must force init-done")
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 0)
      assert((status() & STAT_INIT_DONE) == 0, "clearing the override must clear it again")

      // A real pulse on the socket input latches permanently.
      dut.dbg.logic.initDoneSeen #= true
      dut.clockDomain.waitSampling(3)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(3)
      assert((status() & STAT_INIT_DONE) != 0,
        "init_done_seen must latch sticky so a late-attaching debugger still sees it")
    }
  }

  test("CONTROL honours byte strobes: a zero-strobe write changes nothing") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.waitSampling(20)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, CTRL_COLD_HOLD)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean)
      // WSTRB=0 must not clear the hold even though WDATA is zero.
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CONTROL.toLong, 0x00000000L, strb = 0x0) == 0)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "a WSTRB=0 write must leave CONTROL untouched (spec 3.1)")
      // WSTRB=0xE addresses only bytes 1..3, which hold no CONTROL bits.
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_CONTROL.toLong, 0x00000000L, strb = 0xE) == 0)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "byte 0 not strobed must leave the CONTROL bits untouched")
    }
  }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlCsrSpec" 2>&1 | tail -25
```

Expected: compilation error `value coldResetHold is not a member of ...` — the ports do not exist yet.

- [ ] **Step 3: Write minimal implementation**

In `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`, immediately after the `dbgAxi.setName("dbg_axi")` line, add the socket group:

```scala
    // ── Socket surface (cpu_socket.vh section 6, "SoC-fabric control group") ────────
    val coldResetPulse = out(Bool()).setName("cpu_cold_reset_pulse")
    val coldResetHold  = out(Bool()).setName("cpu_cold_reset_hold")
    val initDoneSeen   = in(Bool()).setName("init_done_seen")
    coldResetPulse.simPublic(); coldResetHold.simPublic(); initDoneSeen.simPublic()
```

Inside the `csr` Area, after the `merged` helper, add the configuration registers:

```scala
      // ── Host configuration, debug reset domain: survives every CPU reset ─────────
      val ctrlInitDoneOvr = RegInit(False); ctrlInitDoneOvr.simPublic()
      val ctrlColdHold    = RegInit(False); ctrlColdHold.simPublic()
      val initDoneSticky  = RegInit(False); initDoneSticky.simPublic()
      /** One-cycle pulse; defaults low every cycle and is set only by a CONTROL write. */
      val coldPulse       = RegInit(False); coldPulse.simPublic()
      coldPulse := False

      // A level input, latched sticky so a debugger attaching after DDR calibration
      // still observes it. Living in the debug domain, it also survives CPU reset.
      when(initDoneSeen) { initDoneSticky := True }

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
```

Replace the empty `when(doWrite) { ... }` block with:

```scala
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
        }
      }
```

Extend the `switch(arAddr)` read arm with:

```scala
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
```

Finally, drive the socket outputs at the end of the `csr` Area, before the assertion block:

```scala
      coldResetPulse := coldPulse
      coldResetHold  := ctrlColdHold
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlCsrSpec m68k040.debug.DebugCtrlAxiSpec"
```

Expected: 14 + 8 = 22 tests pass, `[success]`. `DebugCtrlAxiSpec` needs no change: it never drives `initDoneSeen`, and SpinalSim leaves an unpoked input at 0.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala \
        src/test/scala/m68k040/debug/DebugCtrlCsrSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage1): CONTROL/STATUS, init-done latch, and the cold-reset outputs

Implements the CONTROL bits Stage 1 can honestly implement -- bit 3 (init-done
override), bit 4 (cold-reset hold level, driving cpu_cold_reset_hold), bit 5
(cold-reset pulse, one core clock on cpu_cold_reset_pulse) and bit 2 as its
deprecated alias -- and makes bits 0/1/7 RAZ/WI rather than storing a halt
request no machinery will ever service. Spec 15.2: a host that writes HALT and
reads back 0 learns the truth immediately instead of polling a STATUS bit that
will never set.

STATUS reads halted=0 / running=1 / exception=0 / auto-halt=0, keeping spec
3.3's "halted and running are mutually exclusive" true, with bit 2 driven by a
sticky latch of the socket's init_done_seen ORed with the override. Both the
latch and the hold live in the debug reset domain, so they survive the CPU
reset the hold itself requests -- the exact self-clearing bug debug_reset_ctl.v
was written to fix.

Byte strobes gate the CONTROL write through the shared merged() helper, so a
WSTRB=0 or byte-0-unstrobed write leaves the register untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: RAM window, monitor sense, and `cfg_wipe`

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Create: `src/test/scala/m68k040/debug/DebugCtrlSocketSpec.scala`
- Test: `src/test/scala/m68k040/debug/DebugCtrlSocketSpec.scala`

**Interfaces:**
- Consumes: `csr.{doWrite, doRead, awAddr, arAddr, wStrb, rData, merged}` (Task 7); `DebugRegMap.{OFF_RAM_WINDOW_LG2, OFF_MON_SENSE, OFF_DBG_RESET_CTL, RAM_WINDOW_LG2_POR, RAM_WINDOW_LG2_MIN, RAM_WINDOW_LG2_MAX, MON_SENSE_POR}` (Task 4).
- Produces, relied on by Tasks 11-12:
  - core-level ports `ramWindowLg2: UInt` (6 bits, `cpu_ram_window_lg2`) and `monSense: UInt` (7 bits, `cpu_mon_sense`);
  - debug-domain registers `csr.ramWindow: UInt` (6 bits, init 26) and `csr.mon: UInt` (7 bits, init 0x06), both `simPublic()`;
  - `csr.cfgWipe: Bool` — a one-cycle strobe raised by `OFF_DBG_RESET_CTL` bit 0, `simPublic()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/DebugCtrlSocketSpec.scala`:

```scala
package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The SoC-fabric control group (`cpu_socket.vh` section 6) and the `cfg_wipe` escape
  * hatch. Every POR default and clamp bound is taken from `DebugRegMap`, which is
  * generated from `tools/debug/debug_regmap.def`, which in turn is checked against
  * `macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:1805,1814,2202-2206`. */
class DebugCtrlSocketSpec extends AnyFunSuite {

  private val DRC_CFG_WIPE = 1L << 0

  private def settle(dut: DebugCtrlDut): Unit = {
    dut.clockDomain.forkStimulus(10)
    DbgAxiDriver.idle(dut.axi)
    dut.dbg.logic.initDoneSeen #= false
    dut.clockDomain.waitSampling(20)
  }

  test("power-on defaults match the deployed controller") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_POR,
        s"cpu_ram_window_lg2 POR = ${dut.dbg.logic.ramWindowLg2.toInt}, " +
        s"expected ${DebugRegMap.RAM_WINDOW_LG2_POR}")
      assert(dut.dbg.logic.monSense.toInt == DebugRegMap.MON_SENSE_POR,
        s"cpu_mon_sense POR = ${dut.dbg.logic.monSense.toInt}, " +
        s"expected ${DebugRegMap.MON_SENSE_POR}")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == DebugRegMap.RAM_WINDOW_LG2_POR.toLong)
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == DebugRegMap.MON_SENSE_POR.toLong)
    }
  }

  test("the RAM window round-trips and drives cpu_ram_window_lg2") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- DebugRegMap.RAM_WINDOW_LG2_MIN to DebugRegMap.RAM_WINDOW_LG2_MAX) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong) == 0)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == v,
          s"cpu_ram_window_lg2 = ${dut.dbg.logic.ramWindowLg2.toInt}, wrote $v")
        assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == v.toLong)
      }
    }
  }

  test("the RAM window clamps below the minimum and above the maximum") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- 0 until DebugRegMap.RAM_WINDOW_LG2_MIN) {
        DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_MIN,
          s"writing $v must clamp up to ${DebugRegMap.RAM_WINDOW_LG2_MIN}")
      }
      for (v <- (DebugRegMap.RAM_WINDOW_LG2_MAX + 1) until 64) {
        DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, v.toLong)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_MAX,
          s"writing $v must clamp down to ${DebugRegMap.RAM_WINDOW_LG2_MAX}")
      }
    }
  }

  test("all 128 monitor-sense encodings are accepted unclamped") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      for (v <- 0 until 128) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_MON_SENSE.toLong, v.toLong) == 0)
        dut.clockDomain.waitSampling(2)
        assert(dut.dbg.logic.monSense.toInt == v,
          s"cpu_mon_sense = ${dut.dbg.logic.monSense.toInt}, wrote $v")
      }
      // Bits above 6 are ignored, not stored.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0xFFL)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.monSense.toInt == 0x7F, "only bits [6:0] are stored")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == 0x7FL, "the readback must not leak bit 7")
    }
  }

  test("both fields honour byte strobes") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 24L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x2AL)
      dut.clockDomain.waitSampling(2)
      // WSTRB with byte 0 masked off must leave both untouched.
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 0x0000001EL, strb = 0xE)
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong, 0x00000000L, strb = 0xE)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.ramWindowLg2.toInt == 24, "byte 0 unstrobed must not change it")
      assert(dut.dbg.logic.monSense.toInt == 0x2A, "byte 0 unstrobed must not change it")
    }
  }

  test("cfg_wipe restores exactly the deployed wipe list and answers its own write") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      // Move everything away from its POR value, including a bit the deployed wipe
      // deliberately does NOT clear.
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 30L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x7FL)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 1L << 4)
      dut.clockDomain.waitSampling(2)
      assert(dut.dbg.logic.coldResetHold.toBoolean)

      // The wipe's own write MUST receive its B response -- spec 15.3 and
      // debug_ctrl.v:3016-3024: "Deliberately NOT a reset: it leaves the AXI slave FSM
      // alone, so the very write that requested the wipe still receives its B response."
      val resp = DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE)
      assert(resp == 0, s"the cfg-wipe write must answer OKAY, got $resp")
      dut.clockDomain.waitSampling(3)

      assert(dut.dbg.logic.ramWindowLg2.toInt == DebugRegMap.RAM_WINDOW_LG2_POR,
        "cfg_wipe must restore the RAM window to its POR default")
      assert(dut.dbg.logic.monSense.toInt == DebugRegMap.MON_SENSE_POR,
        "cfg_wipe must restore the monitor sense to its POR default")
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "cfg_wipe must NOT clear cold-reset hold -- the deployed wipe list does not, and " +
        "feature bit 2 has to mean the same thing on both cores (spec 15.3)")

      // The slave is still alive afterwards.
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_VERSION.toLong) == DebugRegMap.VERSION_VALUE.toLong)
    }
  }

  test("cfg_wipe is a one-cycle strobe, not a level") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      var high = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.csr.cfgWipe.toBoolean) high += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE)
      dut.clockDomain.waitSampling(20)
      watcher.terminate()
      assert(high == 1, s"cfg_wipe was high for $high cycles, expected exactly 1")
      // A wipe request with byte 0 unstrobed must not fire at all.
      var high2 = 0
      val watcher2 = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dbg.logic.csr.cfgWipe.toBoolean) high2 += 1
        }
      }
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_CFG_WIPE, strb = 0xE)
      dut.clockDomain.waitSampling(20)
      watcher2.terminate()
      assert(high2 == 0, "an unstrobed byte 0 must not raise cfg_wipe")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlSocketSpec" 2>&1 | tail -25
```

Expected: compilation error `value ramWindowLg2 is not a member of ...`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`, extend the socket group (after `initDoneSeen`):

```scala
    val ramWindowLg2 = out(UInt(6 bits)).setName("cpu_ram_window_lg2")
    val monSense     = out(UInt(7 bits)).setName("cpu_mon_sense")
    ramWindowLg2.simPublic(); monSense.simPublic()
```

Inside the `csr` Area, after `controlWord`, add:

```scala
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
```

Extend the `switch(awAddr)` write arm with:

```scala
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
              // Bit 1 (CPU-reset count clear) is added by Task 11.
            }
          }
```

Add the wipe action AFTER the `when(doWrite)` block, so a later `when` overrides a
same-cycle configuration write exactly as the deployed controller's arm ordering does:

```scala
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
```

Extend the `switch(arAddr)` read arm with:

```scala
          is(DebugRegMap.OFF_RAM_WINDOW_LG2) { rData := ramWindowWord }
          is(DebugRegMap.OFF_MON_SENSE)      { rData := monSenseWord }
```

And drive the two new outputs alongside the cold-reset pair:

```scala
      ramWindowLg2 := ramWindow
      monSense     := mon
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlSocketSpec m68k040.debug.DebugCtrlCsrSpec m68k040.debug.DebugCtrlAxiSpec"
```

Expected: 7 + 14 + 8 = 29 tests pass, `[success]`.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala \
        src/test/scala/m68k040/debug/DebugCtrlSocketSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage1): RAM-window / monitor-sense registers and the cfg_wipe escape hatch

Adds cpu_ram_window_lg2 (POR 26, write-clamped to [22,30]) and cpu_mon_sense
(POR 0x06, all 128 encodings accepted unclamped -- every one is meaningful to
the SoC's video.v sense_response()), both in the debug reset domain so an
operator can set a sense code and then reset into it.

OFF_DBG_RESET_CTL bit 0 raises a one-cycle cfg_wipe strobe that restores those
POR defaults. Following debug_ctrl.v:3016-3024 exactly, the wipe is NOT a reset:
it leaves the AXI FSM alone so the very write that requested it still gets its
B response, and it deliberately does NOT clear cold-reset hold or the init-done
override, because the deployed wipe list does not either and feature bit 2 has
to mean the same thing on both cores.

The wipe is applied in a later `when` than the write decode, reproducing the
deployed module's "overrides an in-flight config write without needing its own
priority encoder" ordering.

Every POR value and clamp bound is read from DebugRegMap, generated from
debug_regmap.def, which the Stage-0 conformance test checks against
debug_ctrl.v:1805/1814/2202-2206.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: CPU-reset observation, the surviving counter, and the §13 assertions

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Create: `src/test/scala/m68k040/debug/DebugCtrlResetSpec.scala`
- Test: `src/test/scala/m68k040/debug/DebugCtrlResetSpec.scala`

**Interfaces:**
- Consumes: `csr.{doWrite, doRead, awAddr, arAddr, wData, wStrb, rData, ramWindow, mon, ctrlColdHold, ctrlInitDoneOvr, cfgWipe}` (Tasks 7, 9, 10); `logic.coreCd` (Task 7); `DebugRegMap.OFF_DBG_RESET_CTL` (Task 4).
- Produces: `csr.cpuRstEvent: Bool` and `csr.cpuResetCount: UInt` (16 bits, saturating), both `simPublic()`; the `OFF_DBG_RESET_CTL` read arm; and the two remaining §13 assertions. This completes the Stage-1 RTL.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/DebugCtrlResetSpec.scala`:

```scala
package m68k040.debug

import m68k040.M68kSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 10.1: "The AXI slave and cold-reset-hold register must never be reset by
  * the CPU reset they request. A synchronized CPU-reset edge increments the surviving
  * reset counter and clears runtime state without disturbing accepted AXI handshakes or
  * host configuration."
  *
  * This is the property the sibling's 2026-07-26 fix exists for, so it is tested directly
  * rather than inferred: configuration written before a CPU reset must still be there
  * afterwards, and a transaction accepted before the reset must still be answered. */
class DebugCtrlResetSpec extends AnyFunSuite {

  private val DRC_CFG_WIPE    = 1L << 0
  private val DRC_COUNT_CLEAR = 1L << 1

  private def settle(dut: DebugCtrlDut): Unit = {
    dut.clockDomain.forkStimulus(10)
    DbgAxiDriver.idle(dut.axi)
    dut.dbg.logic.initDoneSeen #= false
    dut.clockDomain.waitSampling(20)
  }

  /** Assert then release the SOCKET reset -- the one the debug domain observes but must
    * not consume. */
  private def cpuReset(dut: DebugCtrlDut, cycles: Int = 5): Unit = {
    dut.clockDomain.assertReset()
    dut.clockDomain.waitSampling(cycles)
    dut.clockDomain.deassertReset()
    dut.clockDomain.waitSampling(5)
  }

  test("host configuration survives a CPU reset") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_RAM_WINDOW_LG2.toLong, 29L)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x4BL)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_CONTROL.toLong, 1L << 4)
      dut.clockDomain.waitSampling(2)

      cpuReset(dut)

      assert(dut.dbg.logic.ramWindowLg2.toInt == 29,
        s"the RAM window was wiped by the CPU reset (${dut.dbg.logic.ramWindowLg2.toInt})")
      assert(dut.dbg.logic.monSense.toInt == 0x4B,
        s"the monitor sense was wiped by the CPU reset (${dut.dbg.logic.monSense.toInt})")
      assert(dut.dbg.logic.coldResetHold.toBoolean,
        "cold-reset hold cleared itself across the reset it requested -- the exact " +
        "self-clearing bug debug_reset_ctl.v exists to prevent")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_RAM_WINDOW_LG2.toLong) == 29L, "readback after reset")
    }
  }

  test("an accepted transaction is still answered when CPU reset asserts mid-flight") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      settle(dut)
      // Accept AW+W, hold BREADY low, then assert the CPU reset. Spec 3.1: "never clear
      // an accepted request merely because CPU reset asserted".
      b.bready #= false
      b.awaddr #= DebugRegMap.OFF_RAM_WINDOW_LG2.toLong; b.awvalid #= true
      b.wdata  #= 27L; b.wstrb #= 0xF; b.wvalid #= true
      dut.clockDomain.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
      b.awvalid #= false; b.wvalid #= false
      dut.clockDomain.waitSamplingWhere(b.bvalid.toBoolean)

      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(6)
      assert(b.bvalid.toBoolean, "BVALID was dropped by the CPU reset")
      assert(b.bresp.toInt == 0, "BRESP changed across the CPU reset")
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(3)
      assert(b.bvalid.toBoolean, "BVALID was dropped when the CPU reset released")

      b.bready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.bvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "BVALID never cleared")
      assert(dut.dbg.logic.ramWindowLg2.toInt == 27, "the accepted write did not take effect")
    }
  }

  test("the CPU-reset counter counts edges and survives the resets it counts") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      def count(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      def low16(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) & 0xFFFFL

      assert(count() == 0, "the counter must start at zero")
      assert(low16() == 0, "OFF_DBG_RESET_CTL[15:0] reads zero (deployed layout)")
      for (n <- 1 to 5) {
        cpuReset(dut)
        assert(count() == n.toLong, s"after $n resets the counter reads ${count()}")
      }
    }
  }

  test("OFF_DBG_RESET_CTL bit 1 clears the counter") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      def count(): Long =
        DbgAxiDriver.read(dut.axi, dut.clockDomain,
          DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      cpuReset(dut); cpuReset(dut); cpuReset(dut)
      assert(count() == 3)
      assert(DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_COUNT_CLEAR) == 0)
      dut.clockDomain.waitSampling(2)
      assert(count() == 0, "bit 1 must clear the counter")
      // The clear must not be a level: another reset still counts.
      cpuReset(dut)
      assert(count() == 1)
      // And it must respect the byte strobe.
      DbgAxiDriver.write(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong, DRC_COUNT_CLEAR, strb = 0xE)
      dut.clockDomain.waitSampling(2)
      assert(count() == 1, "an unstrobed byte 0 must not clear the counter")
    }
  }

  test("a reset that is already asserted when the debug domain wakes does not count") {
    M68kSim().compile(new DebugCtrlDut(porCyclesArg = 8)).doSim { dut =>
      // Hold the socket reset asserted across the whole debug POR window. The edge
      // detector's reset value is 1 precisely so this does not manufacture an edge
      // (debug_reset_ctl.v:129-139).
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(30)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(10)
      val c = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c == 0, s"a reset already high at POR exit manufactured $c spurious edge(s)")
      cpuReset(dut)
      val c2 = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c2 == 1, s"a genuine later edge must still count (got $c2)")
    }
  }

  test("the counter saturates instead of wrapping back through zero") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      // Force the counter to its top value, then prove one more edge leaves it there.
      // Zero means "no reset observed since clear" and must not be reachable by
      // wraparound (spec 15.4). The poke targets a simPublic register and is stable
      // because the very next edge re-evaluates the saturating guard and holds it; if a
      // future SpinalSim rejects poking an internal Reg, replace this line with 0xFFFF
      // real cpuReset(dut) calls -- slower, same property.
      dut.dbg.logic.csr.cpuResetCount #= 0xFFFF
      dut.clockDomain.waitSampling(2)
      cpuReset(dut)
      val c = DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_DBG_RESET_CTL.toLong) >>> 16
      assert(c == 0xFFFFL, f"the counter wrapped to 0x$c%X instead of saturating")
    }
  }

  test("the debug domain is untouched by a CPU reset held for a long time") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      settle(dut)
      DbgAxiDriver.write(dut.axi, dut.clockDomain, DebugRegMap.OFF_MON_SENSE.toLong, 0x33L)
      dut.clockDomain.waitSampling(2)
      // The Q700 platform holds cpu_rst for the WHOLE boot window (MIG calibration, the
      // SD->DDR ROM copy, a 50 ms settle) -- the window an operator most needs the
      // debugger alive in.
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(400)
      // The slave must still serve reads with the reset asserted.
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_VERSION.toLong) == DebugRegMap.VERSION_VALUE.toLong,
        "the debug slave stopped answering while the CPU reset was held")
      assert(DbgAxiDriver.read(dut.axi, dut.clockDomain,
        DebugRegMap.OFF_MON_SENSE.toLong) == 0x33L, "configuration was lost")
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(5)
      assert(dut.dbg.logic.monSense.toInt == 0x33)
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlResetSpec" 2>&1 | tail -25
```

Expected: compilation error `value cpuResetCount is not a member of ...`.

- [ ] **Step 3: Write minimal implementation**

In the `csr` Area of `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`, add the edge
detector and counter immediately after the AXI FSM registers:

```scala
      // ── CPU reset: OBSERVED, never consumed ────────────────────────────────────
      // The socket `rst` is read as data by registers that this reset does not clear.
      // Reset value True so a `rst` already high when the debug domain leaves POR does
      // not manufacture a spurious edge (debug_reset_ctl.v:129-139).
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
```

Extend the `OFF_DBG_RESET_CTL` write arm (added in Task 10) with bit 1:

```scala
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            when(wStrb(0)) {
              cfgWipe := wData(0)
              when(wData(1)) { cpuResetCount := U(0, 16 bits) }
            }
          }
```

Extend the `switch(arAddr)` read arm:

```scala
          is(DebugRegMap.OFF_DBG_RESET_CTL) {
            // Deployed layout (debug_ctrl.v:1411): {cpu_reset_count_r, 16'd0}.
            rData := cpuResetCount.asBits ## B(0, 16 bits)
          }
```

Finally, add the second required §13 assertion next to the first:

```scala
        // "CPU reset cannot change surviving debug configuration" (spec section 13).
        // The only legal movers of this vector are an applied AXI write and a cfg wipe.
        val cfgVec = ctrlColdHold ## ctrlInitDoneOvr ## ramWindow.asBits ## mon.asBits
        val cfgVecPrev = RegNext(cfgVec) init (
          False ## False ##
          B(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits) ##
          B(DebugRegMap.MON_SENSE_POR, 7 bits))
        assert(!(cpuRstLevel && !doWrite && !cfgWipe && (cfgVec =/= cfgVecPrev)),
          "DebugCtrlPlugin: surviving debug configuration changed while CPU reset was " +
          "asserted (spec section 13)",
          FAILURE)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlResetSpec m68k040.debug.DebugCtrlSocketSpec m68k040.debug.DebugCtrlCsrSpec m68k040.debug.DebugCtrlAxiSpec"
```

Expected: 7 + 7 + 14 + 8 = 36 tests pass, `[success]`, and no `FAILURE` from either
synthesis-excluded assertion.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala \
        src/test/scala/m68k040/debug/DebugCtrlResetSpec.scala
git commit -m "$(cat <<'EOF'
debug(stage1): CPU-reset observation, surviving counter, and the section 13 assertions

The socket `rst` is read as DATA by an edge detector clocked in the debug
domain, whose own reset value is 1 so a reset already asserted when the debug
domain leaves POR does not manufacture a spurious edge. The 16-bit count lives
in the debug domain -- it survives the very resets it counts -- and SATURATES
at 0xFFFF, because zero means "none observed since clear" and must not be
reachable by wraparound. OFF_DBG_RESET_CTL reads {count, 16'd0}, the deployed
layout, and write bit 1 clears it.

Adds the second required assertion: surviving configuration may only change
via an applied AXI write or a cfg wipe, never because CPU reset asserted.

Tests exercise the properties the sibling's 2026-07-26 fix exists for, directly
rather than by inference: configuration written before a CPU reset is still
there afterwards; a B response accepted before the reset is still delivered
after it; and the slave keeps answering reads through a 400-cycle held reset,
which is the Q700 boot window an operator most needs a live debugger in.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Wire into `FullCoreSynth` and verify the generated netlist

**Files:**
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the plugin list inside `object GenFullCoreSynthVerilog`)
- Create: `tools/debug/check_debug_netlist.py`
- Test: `tools/debug/check_debug_netlist.py` run against `generated/M68kFullCoreSynth.v`

**Interfaces:**
- Consumes: `m68k040.debug.DebugCtrlPlugin` (Tasks 7-11); `tools/debug/regmap.py`'s `RegMap.ports` (Task 1).
- Produces: `M68kFullCoreSynth` carrying the 22 socket ports under their exact `cpu_socket.vh` names, plus a reusable structural checker Task 13's gate re-runs.

- [ ] **Step 1: Write the failing test**

Create `tools/debug/check_debug_netlist.py`:

```python
#!/usr/bin/env python3
"""Structural checks on the generated full-core Verilog for the debug slave.

Spec section 12, "Gates for every RTL stage", item 4: "Generated-Verilog lint and
structural checks for multiple drivers, latches, and PRF write collisions." For
Stage 1 the two properties that cannot be proved by simulation alone are:

  1. every socket port exists at the top level, under its exact cpu_socket.vh
     name, with the right direction and width; and
  2. NO debug-domain register is clocked by a process that takes the core's
     asynchronous `reset` -- which is the whole point of the debug reset domain,
     and the thing a careless refactor would silently undo.

Standard library only. Run:
    python3 tools/debug/check_debug_netlist.py [path/to/M68kFullCoreSynth.v]
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import regmap  # noqa: E402

DEFAULT_NETLIST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "generated", "M68kFullCoreSynth.v")

PORT_RE = re.compile(
    r"^\s*(input|output)\s+(?:wire|reg)\s*(?:\[\s*(\d+)\s*:\s*0\s*\])?\s+(\w+)\s*,?\s*$")

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_ports(text):
    """name -> (direction, width). Only the top module's port list is scanned; the
    scan stops at the first ');' that closes it."""
    out = {}
    started = False
    for line in text.splitlines():
        if not started:
            if re.match(r"^\s*module\s+M68kFullCoreSynth\b", line):
                started = True
            continue
        if re.match(r"^\s*\)\s*;", line):
            break
        m = PORT_RE.match(line)
        if m:
            direction, msb, name = m.group(1), m.group(2), m.group(3)
            out[name] = (direction, int(msb) + 1 if msb is not None else 1)
    return out


def async_reset_blocks(text):
    """Bodies of every `always @(posedge clk or posedge reset)` process."""
    bodies = []
    pat = re.compile(r"always\s*@\s*\(\s*posedge\s+clk\s+or\s+posedge\s+reset\s*\)")
    token = re.compile(r"\b(begin|end)\b")
    for m in pat.finditer(text):
        i = text.find("begin", m.end())
        if i < 0:
            continue
        depth = 0
        pos = i
        while pos < len(text):
            t = token.search(text, pos)
            if t is None:
                break
            depth += 1 if t.group(1) == "begin" else -1
            pos = t.end()
            if depth == 0:
                break
        bodies.append(text[i:pos])
    return bodies


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_NETLIST
    if not os.path.exists(path):
        sys.stderr.write("no netlist at %s -- run:\n"
                         "  sbt \"runMain m68k040.top.GenFullCoreSynthVerilog\"\n" % path)
        return 1
    with open(path) as fh:
        text = fh.read()

    rm = regmap.load()
    ports = parse_ports(text)
    check("the top module's port list was parsed", len(ports) > 100,
          "only %d ports found" % len(ports))

    for p in rm.ports:
        got = ports.get(p.name)
        check("port %s exists" % p.name, got is not None,
              "not in the top-level port list")
        if got is None:
            continue
        want_dir = "input" if p.direction == "IN" else "output"
        check("port %s direction is %s" % (p.name, want_dir), got[0] == want_dir,
              "got %s" % got[0])
        check("port %s width is %d" % (p.name, p.width), got[1] == p.width,
              "got %d" % got[1])

    # The debug domain must never take the core's asynchronous reset.
    tainted = set()
    for body in async_reset_blocks(text):
        for name in re.findall(r"\b(DebugCtrlPlugin_\w+)\s*<=", body):
            tainted.add(name)
    check("no DebugCtrlPlugin register is clocked by the core's async reset process",
          not tainted, ", ".join(sorted(tainted)))

    # The power-on counter must be reset-LESS, i.e. carry a declaration initializer.
    por_decls = re.findall(
        r"^\s*reg\s*(?:\[[^\]]*\])?\s*(DebugCtrlPlugin_logic_por_\w+)\s*=\s*[^;]+;",
        text, re.M)
    check("the debug POR registers carry declaration initializers (resetKind = BOOT)",
          len(por_decls) >= 2, "found %r" % (por_decls,))

    # And they must be assigned from a plain, reset-free clocked process.
    plain = re.findall(r"always\s*@\s*\(\s*posedge\s+clk\s*\)", text)
    check("at least one reset-free clocked process exists for the debug POR domain",
          len(plain) >= 1)

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/debug/check_debug_netlist.py; echo "exit=$?"
```

Expected: `FAIL  port dbg_axi_awaddr exists  -- not in the top-level port list` (and 21 more), `exit=1` — the plugin is not in the full core yet.

- [ ] **Step 3: Write minimal implementation**

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, inside `object GenFullCoreSynthVerilog`,
add the build-ID plumbing above the `M68kSpinalConfig(...)` call:

```scala
    // DBG_BUILD_ID is supplied by the SoC build (spec 3.1). It enters as a plugin
    // constructor parameter -- not a Global key (spec 0.9) and not a socket port -- so a
    // bitstream build can stamp it without touching RTL. The value is HEXADECIMAL, with
    // or without a leading 0x; anything else is a hard error rather than a silent 0,
    // because a wrong build ID is worse than none (it makes a stale bitstream look fresh).
    val dbgBuildId: BigInt = sys.env.get("DBG_BUILD_ID") match {
      case None    => BigInt(0)
      case Some(s) =>
        val hex = s.trim.stripPrefix("0x").stripPrefix("0X")
        require(hex.nonEmpty && hex.forall(c => "0123456789abcdefABCDEF".contains(c)),
          s"DBG_BUILD_ID must be hexadecimal (got '$s')")
        BigInt(hex, 16)
    }
```

and add the plugin to the `Seq[FiberPlugin](...)` list, immediately after
`new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu)`:

```scala
          ,
          // Stage 1 of the debug/control slave. Placed LAST because it consumes nothing
          // from any other plugin -- it declares its own socket IO and reads no Global
          // key, so its position in the list is free. Later stages that consume
          // DebugCommitService will need to sit after RobPlugin.
          new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = 1)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/debug/check_debug_netlist.py
grep -nE '^\s*(input|output)\s+wire.*\b(dbg_axi_|cpu_cold_reset_|cpu_ram_window_lg2|cpu_mon_sense|init_done_seen)' generated/M68kFullCoreSynth.v | wc -l
```

Expected: all `PASS` lines and `All checks passed.` from the checker, and `22` from the grep.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/top/FullCoreSynth.scala tools/debug/check_debug_netlist.py
git commit -m "$(cat <<'EOF'
debug(stage1): wire DebugCtrlPlugin into FullCoreSynth + netlist structural check

Adds the plugin to GenFullCoreSynthVerilog's list, taking DBG_BUILD_ID from the
environment so a bitstream build can stamp it without touching RTL -- a
constructor parameter, not a Global key (spec 0.9) and not a socket port.

tools/debug/check_debug_netlist.py proves on the real generated Verilog what
simulation cannot: all 22 socket ports exist at the top level under their exact
cpu_socket.vh names with the right direction and width, NO DebugCtrlPlugin
register is assigned inside an `always @(posedge clk or posedge reset)` process
(i.e. the debug domain genuinely does not take the core's async reset), and the
power-on counter registers really are reset-less with declaration initialisers.

That second check is the one that matters long-term: it is the property a
careless refactor would silently undo, reintroducing exactly the bug
debug_reset_ctl.v was written to fix.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Full acceptance gate

**Files:**
- Create: `.superpowers/sdd/progress-debug-ctrl-stage0-stage1-2026-08-17.md`
- Test: `make test-fast`, `./tools/debug/run_tests.sh`, `tools/debug/check_debug_netlist.py`, and the post-route Vivado gate

**Interfaces:**
- Consumes: everything from Tasks 1-12.
- Produces: the recorded pass/fail evidence for spec §11's gates and the §13 assertion list, in the project's standard progress-ledger form.

- [ ] **Step 1: Run the whole software suite**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g | head -2                       # machine budget: max 2 heavy JVMs, never during vivado
./tools/debug/run_tests.sh
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -30
```

Expected: `run_tests.sh` exits 0 with `All checks passed.` from each script and three
`CURRENT:` lines; `test-fast` reports `[success]` with the pre-existing suite count plus
the five new debug suites (`DebugRegMapSpec` 8, `DebugCtrlAxiSpec` 8, `DebugCtrlCsrSpec`
14, `DebugCtrlSocketSpec` 7, `DebugCtrlResetSpec` 7 — 44 new tests). Any
pre-existing failure must be reproduced on the parent commit before being accepted as
unrelated.

- [ ] **Step 2: Confirm the invariants this plan promised not to break**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git diff --stat $(git merge-base HEAD main) -- src/main/scala/m68k040/Global.scala
grep -c 'Database\.' src/main/scala/m68k040/Global.scala
grep -rn 'Global\.' src/main/scala/m68k040/debug/ || echo "no Global reference in the debug package -- correct"
grep -rn 'trait .*Service' src/main/scala/m68k040/debug/ || echo "no new service trait -- correct for Stage 1"
```

Expected: an empty diff for `Global.scala`; `13` keys; and both "correct" messages.
Spec §0.9: "Add no `Global` database key for debug."

- [ ] **Step 3: Regenerate the netlist and re-run the structural check**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/debug/check_debug_netlist.py
md5sum generated/M68kFullCoreSynth.v
```

Expected: `All checks passed.` and an MD5 recorded for the ledger — the gate script prints
`SOURCE_MD5`/`NETLIST_MD5` and they must match this value, proving the numbers describe
this netlist and not a stale checkpoint.

- [ ] **Step 4: Read the CURRENT FMax reference — do not assume 189.502**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
ls -t .superpowers/sdd/progress-*.md | head -5
grep -rnE 'ACHIEVED_FMAX|POSTROUTE_FULLCORE_RESULT' .superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md | tail -10
ls -t synth/archive/ | head -5
grep -nE 'CLB LUTs|CLB Registers|Slice LUTs|Slice Registers' \
  synth/archive/$(ls -t synth/archive/ | head -1)/fullcore_route_util.rpt | head -10
```

Record: the most recent post-route `ACHIEVED_FMAX`, the commit it belongs to, and that
commit's `CLB LUTs` / `CLB Registers` absolute numbers. Those three values are the
reference for this task's gate. The number recorded when this plan was written was
**189.502 MHz** at `c125d96` with 123602 LUTs / 56029 FF at `866437c`, but task #219's
live campaign may have superseded it — the value read here wins.

If the newest archive does not correspond to this branch's parent commit, produce a real
baseline instead of interpolating:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
# The parent of this plan's FIRST commit -- found by name, never by a hardcoded ~N,
# because a rebase or an extra fix-up commit would silently shift the count.
BASE=$(git rev-parse "$(git log --format='%H %s' | grep -m1 'debug(stage0): machine-readable' | cut -d' ' -f1)^")
echo "baseline commit: $BASE"
git worktree add /tmp/dbg-baseline "$BASE"
cd /tmp/dbg-baseline && ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```

and gate that tree first, serially. Never `git checkout <sha>` in the shared tree
(project standing rule) and never run two Vivado sessions at once.

- [ ] **Step 5: Run the post-route gate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
pgrep -a vivado || echo "no vivado running -- safe to launch"
pgrep -af 'jtag|xsdb|hw_server' || echo "no JTAG session live -- safe to launch"
env IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=9 \
  vivado -mode batch -source synth/impl_FullCore.tcl > synth/gate_debug_stage1.out 2>&1
```

Launch this with the Bash tool's own `run_in_background` parameter, not shell-level
`nohup` — the harness-tracked mechanism reliably fires a completion notification, nested
`nohup` does not. Expect tens of minutes.

Expected in `synth/gate_debug_stage1.out`: `SOURCE_MD5` equal to `NETLIST_MD5` equal to
the Step-3 MD5, zero errors, and a final `POSTROUTE_FULLCORE_RESULT ... ACHIEVED_FMAX_MHZ
<n>` line.

- [ ] **Step 6: Judge the gate against spec §11**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -nE 'SOURCE_MD5|NETLIST_MD5|POSTROUTE_FULLCORE_RESULT|ACHIEVED_FMAX|SIGNOFF' synth/gate_debug_stage1.out
grep -nE 'CLB LUTs|CLB Registers|DSPs|Block RAM Tile' synth/fullcore_route_util.rpt | head -10
grep -nE 'Slack|Source:|Destination:' synth/fullcore_route_timing.rpt | head -60
grep -c 'DebugCtrlPlugin' synth/fullcore_route_timing.rpt
```

The four gate conditions, all quoted from spec §11 and all of which must hold:

1. **LUT delta ≤ +1.0%** of the reference's absolute `CLB LUTs`.
2. **FF delta ≤ +1.0%** of the reference's absolute `CLB Registers`.
3. **No DSP and no BRAM added.** Spec: "no DSP/BRAM except explicitly enabled trace" — Stage 1 has no trace, so the delta must be exactly zero on both.
4. **Achieved FMax within 2% of the current reference** and above the architecture's hard floor.
5. **No top timing endpoint is a debug comparator, CSR read mux, or high-fanout debug enable.** The last grep must find `DebugCtrlPlugin` in none of the reported top paths; if it does, spec §11 prescribes the remedy rather than an exception: "If one appears, add a pipeline/register stage before accepting the slice."

If any condition fails, do NOT accept the slice. The most likely offender is the CSR read
mux; the prescribed fix is to bank the `switch(arAddr)` decode into a registered
two-level mux (decode the high address bits into a bank select in one cycle, the low bits
into a word select in the next), which spec §3.1 explicitly permits: "pipeline the
address/bank decode and read mux as needed. No host command relies on a fixed two-cycle
legacy latency."

- [ ] **Step 7: Write the progress ledger**

Create `.superpowers/sdd/progress-debug-ctrl-stage0-stage1-2026-08-17.md` with, at
minimum: the parent commit; the tasks landed and their commits; the software-suite
results; the reference FMax/LUT/FF triple with its source commit and file; this gate's
absolute numbers and deltas for all five §11 conditions; the `SOURCE_MD5`/`NETLIST_MD5`
match; contention status at launch (load average, free memory, whether any Vivado or JTAG
session was live); and an explicit statement of which §13 assertions are implemented in
RTL (`debug reset cannot accept AXI requests`; `CPU reset cannot change surviving debug
configuration`) versus which are enforced in the test suite instead (`feature bits imply
reachable, non-tied-off behavior` — enforced by `DebugRegMapSpec`, `DebugCtrlCsrSpec` and
`tools/debug/test_dbg_protocol.py`, because it is a statement about the whole build, not
a signal a hardware assertion can observe).

- [ ] **Step 8: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
mkdir -p synth/archive/$(git rev-parse --short HEAD)_debug_stage1_decode_fetch
cp synth/fullcore_route_timing.rpt synth/fullcore_route_util.rpt \
   synth/fullcore_synth_timing.rpt synth/fullcore_synth_util.rpt \
   synth/gate_debug_stage1.out \
   synth/archive/$(git rev-parse --short HEAD)_debug_stage1_decode_fetch/
git add .superpowers/sdd/progress-debug-ctrl-stage0-stage1-2026-08-17.md \
        synth/archive/
git commit -m "$(cat <<'EOF'
debug(stage1): acceptance gate -- post-route numbers, deltas, and the ledger

Records the spec section 11 gate for the Stage 0 + Stage 1 tranche against the
CURRENT post-route reference (read at gate time from the FMax-closure ledger,
not hardcoded): LUT and FF deltas versus the +1.0% budget, zero DSP/BRAM delta
(Stage 1 has no trace), achieved FMax versus the 2% band, and the check that no
top timing endpoint is a debug comparator, CSR read mux, or high-fanout debug
enable.

Also records which section 13 assertions are RTL assertions and which are
enforced by the test suite, and archives the post-route reports next to the
existing gates so the next tranche has a real baseline rather than a quoted
number.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

Performed against this plan before handing it off. Findings were fixed in place, not just
listed.

### Spec coverage — every Stage-0 and Stage-1 requirement maps to a task

| Spec requirement (verbatim source) | Task(s) |
|---|---|
| §12 Stage 0: "Land this architecture addendum before RTL." | 3 |
| §12 Stage 0: "Add one machine-readable register/capability definition in the new repo…" | 1 |
| §12 Stage 0: "…and generate or compare the Scala RTL constants, Tcl constants, and Python constants against it." | 4 (generate + `--check`), 5 (Scala-side compare in `fastTest`) |
| §12 Stage 0: "Import/adapt the sibling fake-REPL host tests." | 2 (offset/feature conformance), 6 (protocol rules) |
| §12 Stage 0: pin "unmapped-zero" | 6, and enforced in RTL by 7 |
| §12 Stage 0: pin "byte strobes" | 6, and enforced in RTL by 9/10 |
| §12 Stage 0: pin "feature-bit honesty" | 1, 4, 5, 6, 8 |
| §12 Stage 0: pin "step command sequence" | 6 (`classify_control_writes`) |
| §12 Stage 0: pin "cache polling" | 6 (`cache_op_poll`) |
| §12 Stage 0: pin "arch-apply polling" | 6 (`arch_apply_poll`) |
| §12 Stage 1: "version/build/features" | 8 |
| §12 Stage 1: "CONTROL/STATUS" | 9 |
| §12 Stage 1: "debug-reset control" | 7 (the domain itself), 10 (`OFF_DBG_RESET_CTL` bit 0) |
| §12 Stage 1: "CPU-reset count" | 11 |
| §12 Stage 1: "cold-reset outputs" | 9 |
| §12 Stage 1: "and RAZ/WI behavior" | 7 (default zero read / dropped write), 9 (per-bit RAZ/WI in CONTROL) |
| §12 Stage 1: unit-test "independent AW/W order" | 7 |
| §12 Stage 1: unit-test "backpressure" | 7 |
| §12 Stage 1: unit-test "byte strobes" | 9, 10, 11 |
| §12 Stage 1: unit-test "reset during idle" | 11 |
| §12 Stage 1: unit-test "CPU reset during accepted transactions" | 11 |
| §12 Stage 1: unit-test "READY-low during debug reset" | 7 |
| §12 Stage 1: "Keep every optional feature bit zero." | 8 (explicit per-bit check of all 16) |
| §3.1 "capture AW and W independently and respond only after both have arrived" | 7 |
| §3.1 "honor `WSTRB` per byte for ordinary RW fields" | 7 (`merged`), 9, 10 |
| §3.1 "return OKAY/zero for unmapped reads and OKAY/drop for unmapped writes" | 7 |
| §3.1 "keep READY low during debug-domain reset" | 7 |
| §3.1 "never clear an accepted request merely because CPU reset asserted" | 11 |
| §3.1 "pipeline the address/bank decode and read mux as needed" | 7 (registered `rData`) |
| §3.1 "hold `RVALID`/`BVALID` and payload stable until accepted" | 7 |
| §3.1 version epoch `0xDEB6_0100`; `DBG_BUILD_ID` supplied by the SoC build | 8, 12 |
| §3.2 frozen offsets reserved, zero for absent, never repurposed | 1, 2, 5, 7 |
| §3.3 CONTROL/STATUS encodings and the step sequence | 3 (§15.2), 6, 9 |
| §3.4 append-only feature discovery, honesty rule | 1, 4, 5, 6, 8 |
| §10.1 two reset classes; AXI slave and cold-reset hold never reset by CPU reset | 7, 11, 12 |
| §10.2 CDC | Not applicable: §10.2's "Baseline integration mandates the existing SoC AXI-Lite async bridge. Everything downstream… runs on the core clock." Nothing in this plan crosses a clock. Recorded here so a reviewer sees it was considered, not missed. |
| §11 area/FMax gates, endpoint rule, "Report absolute utilization and deltas" | 13 |
| §13 "debug reset cannot accept AXI requests" | 7 (RTL assertion) |
| §13 "CPU reset cannot change surviving debug configuration" | 11 (RTL assertion) |
| §13 "feature bits imply reachable, non-tied-off behavior" | 5, 6, 8 (build-level property; recorded as such in 13 Step 7) |
| §14 decision 3 (socket/reset port naming) | 3 |
| §0.9 "Add no `Global` database key for debug." | 13 Step 2 (verified, not merely intended) |

Spec items deliberately **not** covered, with the stage that owns them: §5 service table and
§6 halt/step semantics (Stage 2); §7 architectural access (Stage 3); §8 cache semantics and
§14 items 1/4/5 (Stage 4); §9 watchpoints/A-traps/trace (Stages 5-7); §14 item 2 (Stage 5).

### Placeholder scan

`grep -nE 'TBD|TODO|FIXME|XXX|placeholder|similar to Task|as (above|before)|appropriate error|and so on|etc\.'` over the plan returns exactly one hit, inside a verbatim quotation of spec §3.4 ("placeholder probe"). Every code-touching step contains complete code. Three places intentionally say "added by Task N" — Task 7's empty `when(doWrite)`/read arms and Task 10's note that `OFF_DBG_RESET_CTL` bit 1 arrives in Task 11 — and each is a real, compiling intermediate state whose successor task quotes the full replacement text, not a description of it.

### Type and signature consistency across tasks

Checked every cross-task reference, since each task's implementer sees only their own task.

- `DebugRegMap` members used by later tasks (`DBG_AW`, `DBG_DW`, `VERSION_VALUE`, `POR_CYCLES_DEFAULT`, `RAM_WINDOW_LG2_{POR,MIN,MAX}`, `MON_SENSE_POR`, `OFF_*`, `allOffsets`, `features`, `ports`, `featuresForStage`) are all emitted by Task 4's generator, with the Int-vs-BigInt split stated there (`VERSION_VALUE` is the only constant ≥ 2³¹, so it is the only `BigInt`).
- `DbgAxiLite`'s 17 fields, `DebugCtrlDut`'s `dbg`/`axi`, and `DbgAxiDriver`'s five methods are declared in Task 7 and used unchanged in Tasks 8-11; `strb` is a defaulted 5th parameter on `write` and a required 5th on `writeAwFirst`/`writeWFirst`, matching every call site.
- `csr` members added incrementally (`merged`, `controlWord`, `ctrlColdHold`, `ctrlInitDoneOvr`, `coldPulse`, `initDoneSticky`, `initDoneLatched`, `ramWindow`, `mon`, `cfgWipe`, `cpuRstEvent`, `cpuResetCount`) are each declared before their first use in the elaboration order the tasks impose; the §13 assertion block stays last, so Task 11's assertion can read `doWrite`, `cfgWipe`, `ramWindow` and `mon` regardless of where they were inserted.
- Test counts quoted in each task's Step 4 are consistent and cumulative (8 → 15 → 22 → 29 → 36), and Task 13's total of 44 new tests across five suites matches.

### Stage-0 code was executed, not just written

Every Python file in Tasks 1, 2, 4, 6 and 12 was extracted from this plan into a scratch tree
and run against real source during the review pass. Results:

- `test_debug_regmap.py` — 19/19 PASS against the `.def` file as written above.
- `test_sibling_conformance.py` — 11/11 PASS against the real
  `macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v`, `tb/tests/host/fake_jtag_repl.py` and
  `rtl/soc/cpu_socket.vh`. Every deployed offset is reserved, every shared name agrees, all
  19 deployed feature bits reproduce, and all 22 socket port names appear verbatim.
- `gen_debug_regmap.py` — `--check` correctly reports all three outputs STALE before
  generation and CURRENT after; the emitted Python round-trips
  (`features_for_stage(1) == 0x4000f`, `OFF_DBG_RESET_CTL == 0xa4`,
  `VERSION_VALUE == 0xdeb60100`, and `OFF_LIVE_AREG7 == 0x0214C` distinct from
  `OFF_LIVE_A7 == 0x02108`).
- `test_dbg_protocol.py` — 38/38 PASS.
- `check_debug_netlist.py` — run against the repository's real
  `generated/M68kFullCoreSynth.v`: the port-list parser and the async-reset block extractor
  both work on genuine SpinalHDL output, and the failures reported are exactly the ones Task
  12 Step 2 predicts (`FAIL port dbg_axi_awaddr exists -- not in the top-level port list`,
  and no `DebugCtrlPlugin_logic_por_*` declarations), confirming the red-before-green
  evidence that step asks for.

The Scala and SpinalHDL portions could not be executed during planning (they depend on
artefacts the tasks themselves create); open question 5 records exactly which SpinalHDL
behaviours therefore remain assumed and where each one is proven.

**Three real defects found and fixed during this pass:**

1. **Offset-name collision with the deployed map.** A `BLK OFF_LIVE_A` at `0x02130` expands to `OFF_LIVE_A7 = 0x0214C`, but the deployed controller already uses `OFF_LIVE_A7` for the live *active* A7 at `0x02108` (`debug_ctrl.v:676`, `fake_jtag_repl.py:230`). Task 2's naming-consistency check would have failed. Fixed by naming both live arrays `OFF_LIVE_DREG`/`OFF_LIVE_AREG` and adding the four aliases, with the reason recorded in the `.def` file itself so it cannot be "tidied" back.
2. **A tangled `expect_read` tail** in Task 6's `dbg_protocol.py` (a triple-conditional that always returned `None`) plus a now-dead `_STATEFUL` set, originally patched by a follow-up step. Rewritten correctly in place and the patch step deleted.
3. **Fragile or wrong operational details:** `Reg(UInt(6 bits)) init <Int>` replaced with an explicit `init U(x, 6 bits)`; a hardcoded `HEAD~13` baseline lookup replaced with a commit-message search that survives a rebase; `DBG_BUILD_ID` parsing made a hard error on non-hex instead of silently yielding 0; and Task 13's "four new debug suites" corrected to five.

### Open questions I could not resolve from real source

These are genuine, and are flagged here rather than papered over.

1. **There is no discovery bit for basic manual halt/step.** Frozen bits 0-18 and the reserved 19-23 contain nothing meaning "stop/step is real". So a Stage-1 build is distinguishable from a halt-capable one only by `OFF_VERSION` and by `OFF_CONTROL` bit 0 reading back 0 after a write. Minting a new append-only bit is the right fix but is a spec change outside Stage 0/1 scope, so it is recorded in spec §15.2 as a known gap for Stage 2 to close. **Needs a decision when Stage 2 is planned.**
2. **`OFF_CAP_TRACE` reading zero is a convention, not a contract.** The deployed host model hardcodes `CAP_TRACE_VALUE = 0x01000020` and nothing frozen states that 0 means "absent". This plan reads 0 with feature bits 4/5 clear, which is self-consistent, but a host that reads the depths without checking those bits would compute a zero-length ring. Low risk, no source resolves it.
3. **`cfg_wipe` deliberately does not clear `cold_reset_hold`.** Task 10 copies the deployed wipe list (`debug_ctrl.v:3024-3062`) verbatim, so feature bit 2 means the same thing on both cores — but that means the operator escape hatch cannot recover a board whose CPU the host is holding in reset. The sibling source does not say whether this omission is deliberate; the wipe block calls itself "the escape hatch that a CPU reset used to provide by accident" and simply never mentions the hold. **Wants a joint decision with the SoC side before hardware bring-up.**
4. **Whether the default synth target should always carry the debug slave.** This plan always instantiates it (`stage = 1`), because gating a netlist that does not contain the block would prove nothing about its cost. But spec §11 item 7 requires an `enabled = false` build to remove the AXI/debug plugins entirely, and no source says whether the project wants a debug-free `M68kFullCoreSynth` variant kept for FMax comparison. Adding the plugin permanently re-baselines every future gate.
5. **Three SpinalHDL behaviours are asserted by construction but verified only at execution time.** `AxiLite4SlaveFactory`, `ClockDomain.setSynchronousWith`, `ClockDomainConfig.copy` and `ClockDomain.isResetActive` were each confirmed present in the SpinalHDL 1.14.1 jars this project resolves. What could not be confirmed without elaborating is (a) that `setName` on a `slave(...)` bundle yields exactly `dbg_axi_awaddr`-style port names, (b) that `resetKind = BOOT` emits declaration initializers rather than a reset wire under this project's `MultiPortWritesSymplifier` transformation phase, and (c) that reading `coreCd.isResetActive` from `dbgCd` does not trip the clock-domain-crossing check. Task 7 Step 4 names the fallback for (a) and (c); Task 12's `check_debug_netlist.py` is the real proof of (a) and (b) and will fail loudly rather than silently if either assumption is wrong.

---

## Execution Handoff

Two supported ways to run this plan. **Subagent-Driven is recommended** — the tasks are
sequential with hard interface contracts between them, and each ends in its own commit, so
per-task isolation plus a review gate catches drift early.

### Option A — Subagent-Driven Execution (recommended)

Use `superpowers:subagent-driven-development`. Dispatch one subagent per task, in order,
each receiving that task's section verbatim plus these standing rules:

- The task's **Interfaces / Consumes** block lists everything already built; do not re-derive
  or re-implement it, and do not change a signature another task depends on.
- Run every step in order and check its box. A step that fails is a stop, not a warning.
- Any isolated before/after verification uses `git worktree add`; never `git checkout <sha>`
  in the shared tree (project standing rule, task #199 collision).
- Tasks 5 and 7-12 run `sbt`. Respect the machine budget: at most two heavy JVMs
  concurrently, and none at all while Vivado is running (`free -g` before starting).
- Task 13 is the only task that runs Vivado. Confirm no other Vivado session and no live
  JTAG session first, and launch it with the Bash tool's `run_in_background` parameter
  rather than shell-level `nohup`.
- Report the exact command output for each Step 2 (failing) and Step 4 (passing) run — the
  red-then-green evidence, not a summary of it.

Suggested review gates: after **Task 6** (the whole Stage-0 contract, before any RTL exists)
and after **Task 12** (all Stage-1 RTL, before spending a Vivado slot on Task 13).

### Option B — Inline Execution

Use `superpowers:executing-plans` and work through Tasks 1-13 in this session, checking
boxes as you go. Same standing rules apply. This is the better choice if you want to watch
the SpinalHDL elaboration behaviour in open question 5 resolve interactively, since Tasks
7 and 12 are where those three assumptions get tested and a fallback may be needed.

### Either way

- The plan's parent commit is whatever `git rev-parse HEAD` reports at start; record it in
  Task 13's ledger.
- Do not hardcode 189.502 MHz. Task 13 Step 4 re-reads the current reference; task #219's
  live campaign may have moved it.
- If Task 13's gate fails spec §11 condition 5 (a debug endpoint in the top timing paths),
  the spec prescribes the remedy — "add a pipeline/register stage before accepting the
  slice" — and §3.1 explicitly permits banking the CSR read mux. Do that rather than
  accepting the slice with an exception.
