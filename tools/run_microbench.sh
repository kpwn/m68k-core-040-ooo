#!/usr/bin/env bash
# tools/run_microbench.sh -- re-runnable driver for the 68040 OoO latency
# microbenchmark suite (src/test/scala/m68k040/bench/MicrobenchSpec.scala).
#
# This exists so the numbers in docs/superpowers/specs/*-hw-microbenchmark-suite.md
# can be REPRODUCED rather than trusted. It is deliberately a script and not a REPL
# recipe: a completing agent's background processes die with it, and an ad-hoc
# invocation cannot be diffed when a number moves.
#
# Usage:
#   tools/run_microbench.sh                      # default: zero-latency memory
#   tools/run_microbench.sh l2                   # L2-faithful model, 5cyc/70cyc
#   tools/run_microbench.sh l2:5:20              # explicit hit:dram
#   MB_ONLY=validation,divmul tools/run_microbench.sh
#   MB_SEEDS=5 tools/run_microbench.sh
#
#   tools/run_microbench.sh --dram-sweep         # the DRAM sensitivity sweep:
#       re-runs the memory group at several dramCycles values so the SLOPE (how
#       much memory latency the core fails to hide) and INTERCEPT (core-side fixed
#       miss cost) can be extracted. Those two are real; the absolute DRAM number
#       is NOT -- dramCycles is an UNMEASURED model parameter.
#
# HOST DISCIPLINE (this box has 29 GB and several agents share it):
#   refuses to start if >=2 heavy JVMs are already running, or if a Vivado build
#   holds /var/tmp/m68k-ooo-vivado.lock. Override with MB_FORCE=1 at your own risk.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$(pwd)"
OUT="${MB_OUT:-$REPO/bench_logs}"
mkdir -p "$OUT"

# ---- host guards -------------------------------------------------------------
if [ "${MB_FORCE:-0}" != "1" ]; then
  # NEVER `pgrep -af 'vivado.*-mode batch'` -- that pattern self-matches.
  if ! flock -n /var/tmp/m68k-ooo-vivado.lock -c true 2>/dev/null; then
    echo "REFUSING: a Vivado build holds /var/tmp/m68k-ooo-vivado.lock." >&2
    echo "          Simulation during a Vivado build OOM-kills both. Wait, or MB_FORCE=1." >&2
    exit 1
  fi
  # Count heavy JVMs (>1 GiB RSS). Two siblings already simulating is the cap.
  heavy=$(ps -eo rss,comm --no-headers 2>/dev/null | awk '$2 ~ /java/ && $1 > 1048576' | wc -l)
  if [ "$heavy" -ge 2 ]; then
    echo "REFUSING: $heavy heavy JVMs already running (cap is 2 across all agents)." >&2
    ps -eo pid,rss,comm --sort=-rss | grep -i java | head -5 >&2 || true
    echo "          Wait for a slot, or MB_FORCE=1 if you know the box is yours." >&2
    exit 1
  fi
  avail=$(free -g | awk '/^Mem:/{print $7}')
  if [ "${avail:-0}" -lt 6 ]; then
    echo "REFUSING: only ${avail} GiB available; this run needs ~6 GiB." >&2
    exit 1
  fi
fi

COMMIT=$(git rev-parse --short HEAD)
run_one() {
  local mem="$1" tag="$2"
  local log="$OUT/microbench_${tag}_${COMMIT}.log"
  echo "=== IPC_MEM=$mem -> $log"
  # -mem 4g keeps the JVM inside the shared budget; the suite reuses one compiled
  # DUT so memory is dominated by the Verilator model, not the kernel corpus.
  env IPC_MEM="$mem" \
    sbt -mem 4096 "testOnly m68k040.bench.MicrobenchSpec" 2>&1 | tee "$log"
  echo "--- wrote $log"
}

if [ "${1:-}" = "--dram-sweep" ]; then
  # Sensitivity sweep. The memory group only; three DRAM values. Extract the slope
  # of cold-miss latency vs dramCycles: slope ~1.0 means the core hides nothing of
  # a memory stall on a dependent chain (expected for a pointer chase); the
  # intercept is the core-side fixed cost, which IS a real property of this RTL.
  export MB_ONLY="${MB_ONLY:-memory}"
  for d in 20 40 70; do run_one "l2:5:$d" "dram$d"; done
  echo
  echo "DRAM SWEEP DONE. Compare the 'cold miss -> model DRAM' row across:"
  ls -1 "$OUT"/microbench_dram*_"$COMMIT".log
  echo "Report the SLOPE and INTERCEPT, never the absolute DRAM latency:"
  echo "  dramCycles is UNMEASURED in both repos (AxiMemModel.scala header)."
  exit 0
fi

MEM="${1:-zero}"
case "$MEM" in
  zero) run_one zero ideal ;;
  *)    run_one "$MEM" "$(echo "$MEM" | tr ':' '_')" ;;
esac
