#!/usr/bin/env bash
# Post-route synth gate for the walker -> D-cache passthrough work.
#
# WHY THIS IS A COMMITTED SCRIPT RATHER THAN A BACKGROUND PROCESS.
# A polling process started by an agent dies with that agent, which has already
# silently lost experiments on this host. A committed launcher survives: anyone
# (or any later session) can run it, it queues on the Vivado mutex instead of
# forcing its way in, and it refuses to start while other agents' JVMs are
# resident -- a KU5P full_impl peaks around 12-15 GB and this host has 29 GB
# total, so adding it to a couple of live sbt JVMs OOM-kills something.
#
# Usage:
#   synth/run_walker_dcache_gate.sh <git-ref> <label> [more refs/labels...]
#
# Example (the gate this work needs -- baseline first, then the change):
#   synth/run_walker_dcache_gate.sh bb3bca1 baseline HEAD walker-dcache
#
# Each arm:
#   1. waits for the Vivado mutex (flock, blocking -- it QUEUES, never forces),
#   2. waits for the host's total JVM RSS to fall below JVM_QUIET_MB,
#   3. checks out <git-ref> into a scratch worktree,
#   4. regenerates M68kFullCoreSynth.v there,
#   5. runs the same postroute recipe both arms use, and
#   6. appends the verdict lines to synth/walker_dcache_gate_<label>.summary.
#
# Both arms MUST run under the same POSTROUTE_ROUNDS and on an uncontended
# machine; FMax on this host has been measured at 214.3 vs 163.9 MHz for an
# IDENTICAL commit under contention.
#
# CLOCK DOMAIN. Quote `SIGNOFF_200MHZ_*` and the `clk` domain explicitly when
# reporting. The headline row of timing_summary.rpt is NOT the CPU clock and has
# misled this campaign twice.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK=/var/tmp/m68k-ooo-vivado.lock
POSTROUTE_ROUNDS="${POSTROUTE_ROUNDS:-9}"
JVM_QUIET_MB="${JVM_QUIET_MB:-2500}"
SBT="${SBT:-$HOME/sbt/bin/sbt}"
WORKTREE_ROOT="${WORKTREE_ROOT:-/home/qwertyoruiop/m68k-core-040-ooo-worktrees}"

jvm_rss_mb() {
  ps -eo rss,comm 2>/dev/null | grep -i java | awk '{s+=$1} END {printf "%d", s/1024}'
}

wait_for_quiet_jvms() {
  local waited=0
  while [ "$(jvm_rss_mb)" -ge "$JVM_QUIET_MB" ]; do
    if [ $((waited % 300)) -eq 0 ]; then
      echo "[gate] waiting for JVMs to drain: $(jvm_rss_mb) MB resident (want < ${JVM_QUIET_MB} MB)"
    fi
    sleep 30
    waited=$((waited + 30))
  done
  echo "[gate] JVMs quiet ($(jvm_rss_mb) MB); free: $(free -m | awk '/^Mem:/{print $7}') MB available"
}

run_arm() {
  local ref="$1" label="$2"
  local wt="${WORKTREE_ROOT}/gate-${label}"
  local out="${REPO_ROOT}/synth/walker_dcache_gate_${label}.out"
  local sum="${REPO_ROOT}/synth/walker_dcache_gate_${label}.summary"

  echo "[gate] ===== arm '${label}' at ref '${ref}' ====="
  if [ ! -d "$wt" ]; then
    git -C "$REPO_ROOT" worktree add --detach "$wt" "$ref"
  else
    git -C "$wt" checkout --detach "$ref"
  fi

  wait_for_quiet_jvms
  echo "[gate] generating the netlist for '${label}'"
  ( cd "$wt" && "$SBT" -mem 4096 'runMain m68k040.top.GenFullCoreSynthVerilog' ) 2>&1 | tail -3
  local md5
  md5="$(md5sum "$wt/generated/M68kFullCoreSynth.v" | cut -d' ' -f1)"
  echo "[gate] netlist md5 for '${label}': $md5"

  wait_for_quiet_jvms
  echo "[gate] queueing on the Vivado mutex for '${label}' (blocking; never forced)"
  ( cd "$wt" && flock "$LOCK" env POSTROUTE_ROUNDS="$POSTROUTE_ROUNDS" \
      vivado -mode batch -source synth/impl_FullCore.tcl ) > "$out" 2>&1 || true

  {
    echo "===== walker/D-cache postroute gate: arm '${label}' ====="
    echo "ref            : ${ref} ($(git -C "$wt" rev-parse --short HEAD))"
    echo "netlist md5    : ${md5}"
    echo "POSTROUTE_ROUNDS: ${POSTROUTE_ROUNDS}"
    echo "finished       : $(date -Is)"
    echo "--- verdict lines (clk domain; SIGNOFF_* is re-derived at a real 5.000 ns) ---"
    grep -E "SOURCE_MD5|NETLIST_MD5|POSTROUTE_ROUND [0-9]+ WNS|POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|SIGNOFF_200MHZ_WNS_NS|SIGNOFF_200MHZ_RESULT|ACHIEVED_FMAX_MHZ|TNS|failing endpoint" \
      "$out" || echo "(no verdict lines found -- inspect ${out})"
  } | tee "$sum"
}

if [ "$#" -lt 2 ] || [ $(( $# % 2 )) -ne 0 ]; then
  echo "usage: $0 <git-ref> <label> [<git-ref> <label> ...]" >&2
  exit 2
fi

while [ "$#" -ge 2 ]; do
  run_arm "$1" "$2"
  shift 2
done

echo "[gate] all arms complete. Summaries:"
ls -1 "${REPO_ROOT}"/synth/walker_dcache_gate_*.summary
