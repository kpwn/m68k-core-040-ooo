#!/usr/bin/env bash
# Distil one arm of synth/run_walker_dcache_gate.sh into the numbers a reviewer needs.
#
# Kept SEPARATE from the launcher on purpose: bash reads a script incrementally, so
# editing run_walker_dcache_gate.sh while a multi-hour gate is executing it can corrupt
# the running process. This file can be written and re-written freely mid-gate.
#
# usage: synth/summarize_gate_arm.sh <label>          # e.g. baseline | walker-dcache
#
# WHY EACH FIELD:
#   SIGNOFF_200MHZ_*  -- the verdict, re-derived by impl_FullCore.tcl at a real 5.000 ns.
#                        This is the number to quote. The headline row of
#                        timing_summary.rpt is NOT the CPU clock and has misled this
#                        campaign twice; `clk` is the only clock in this netlist.
#   POSTROUTE_ROUND n -- per-round WNS, so a plateau is visible rather than inferred.
#   TNS / endpoints   -- a WNS tie with a 20x TNS difference is not a tie.
#   CLB LUTs / FFs    -- utilisation, from the arm's own fullcore_route_util.rpt (each arm
#                        runs in its own worktree, so the reports do not collide).
set -uo pipefail

LBL="${1:?usage: $0 <label>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WT_ROOT="${WORKTREE_ROOT:-/home/qwertyoruiop/m68k-core-040-ooo-worktrees}"
OUT="$ROOT/synth/walker_dcache_gate_${LBL}.out"
WT="$WT_ROOT/gate-${LBL}"
UTIL="$WT/synth/fullcore_route_util.rpt"
TIMING="$WT/synth/fullcore_route_timing.rpt"

echo "===== arm '${LBL}' ====="
[ -d "$WT" ] && echo "ref            : $(git -C "$WT" rev-parse --short HEAD 2>/dev/null)"
[ -f "$WT/generated/M68kFullCoreSynth.v" ] && \
  echo "netlist md5    : $(md5sum "$WT/generated/M68kFullCoreSynth.v" | cut -d' ' -f1)"

echo "--- verdict (clk domain; SIGNOFF_* re-derived at a real 5.000 ns) ---"
grep -hE "SIGNOFF_200MHZ_WNS_NS|SIGNOFF_200MHZ_RESULT|ACHIEVED_FMAX_MHZ|POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT" \
  "$OUT" 2>/dev/null || echo "(no verdict lines -- arm incomplete?)"

echo "--- per-round WNS (plateau visible, not inferred) ---"
grep -hE "POSTROUTE_ROUND [0-9]+" "$OUT" 2>/dev/null | tail -12 || echo "(none)"

echo "--- TNS / failing endpoints (clk) ---"
if [ -f "$TIMING" ]; then
  awk '/Design Timing Summary/{f=1} f&&/WNS|TNS|Failing Endpoints|Total Endpoints/{print} f&&/^$/{n++; if(n>3) exit}' \
    "$TIMING" | head -12
else
  grep -hE "Total Negative Slack|Number of Failing Endpoints|TNS" "$OUT" 2>/dev/null | head -6 \
    || echo "(no routed timing report at $TIMING)"
fi

echo "--- utilisation ---"
if [ -f "$UTIL" ]; then
  grep -E "CLB LUTs|CLB Registers|Block RAM Tile|DSPs|LUT as Logic|LUT as Memory" "$UTIL" | head -8
else
  echo "(no $UTIL)"
fi
