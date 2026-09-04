#!/usr/bin/env bash
# Part 131 postroute synth gate, runnable unattended.
#
# Committed as a SCRIPT rather than left as a polling background process on purpose:
# a completing agent's background processes die with it, which has already silently
# lost an experiment on this project.
#
# The gate is POSTROUTE, not OOC: the OOC gate has a measured ~0.919 ns noise floor,
# which is larger than the entire margin this campaign is arguing over. Flow is the
# project-standard `synth/impl_FullCore.tcl` at the 5.000 ns / 200 MHz sign-off
# constraint with POSTROUTE_ROUNDS at its default 9 -- the same flow and settings that
# produced the afbabdd baseline (WNS -0.037 ns / 198.531 MHz / 73 failing endpoints,
# CPU `clk` domain).
#
# CONTENTION IS DETECTED VIA THE REAL MUTEX, `flock` on
# /var/tmp/m68k-ooo-vivado.lock -- never via `pgrep -af 'vivado.*-mode batch'`.
# That pgrep pattern SELF-MATCHES: the pgrep command line itself contains the string,
# so on an idle host it reports contention against itself. It falsely reported a busy
# host for 8 consecutive attempts on 2026-09-04 and burned an experiment's whole retry
# budget. The same bug still lives in macqd700-soc's tools/build_bitstream.sh:121 on
# mainline; it is fixed ONLY in the p123-reseed worktree, as an UNCOMMITTED diff, and
# needs upstreaming.
#
# This script BLOCKS on the lock (flock without -n) rather than failing, so it queues
# safely behind any build already running.
#
# usage: tools/run_p131_synth_gate.sh [LOGFILE]
set -u
cd "$(dirname "$0")/.."
LOG=${1:-synth/p131_impl_gate.out}

echo "[p131-gate] netlist md5: $(md5sum generated/M68kFullCoreSynth.v)"
echo "[p131-gate] waiting for /var/tmp/m68k-ooo-vivado.lock ... $(date)"
flock /var/tmp/m68k-ooo-vivado.lock \
  vivado -mode batch -nojournal -source synth/impl_FullCore.tcl > "$LOG" 2>&1
rc=$?
echo "[p131-gate] vivado rc=$rc $(date)"

# Report the CPU `clk` domain numbers explicitly. The headline WNS at the top of
# timing_summary.rpt belongs to the dbg_hub JTAG clock, NOT to `clk`, and reading the
# wrong one has already misled this campaign once. impl_FullCore.tcl's own
# POSTROUTE_FULLCORE_* / SIGNOFF_200MHZ_* lines are computed from `get_timing_paths
# -setup` on the constrained design, i.e. the CPU clock -- quote those.
grep -E 'POSTROUTE_FULLCORE_|SIGNOFF_200MHZ_|ACHIEVED_FMAX_MHZ|FULLCORE_(TNS|LUT|FAILING)' "$LOG" | tail -30
echo "[p131-gate] done $(date)"
