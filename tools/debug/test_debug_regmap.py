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
