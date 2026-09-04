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
#   2. waits until the host actually has room for a full_impl (see wait_for_room),
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
# A KU5P full_impl peaks around 12-15 GB on this 29 GB host, so this is the real
# admission condition: enough FREE memory for the peak plus headroom.
NEED_FREE_MB="${NEED_FREE_MB:-17000}"
# Secondary guard on the project's "at most 2 heavy JVMs, none during Vivado" rule.
# Deliberately NOT a hard "JVM RSS must be ~0": other agents keep idle sbt SERVERS
# resident for hours at 3-4 GB, and gating on that livelocks the gate forever while
# the host in fact has 19+ GB free. An earlier revision of this script did exactly
# that and sat waiting through a completely idle machine.
MAX_HEAVY_JVMS="${MAX_HEAVY_JVMS:-2}"
SBT="${SBT:-$HOME/sbt/bin/sbt}"
WORKTREE_ROOT="${WORKTREE_ROOT:-/home/qwertyoruiop/m68k-core-040-ooo-worktrees}"

jvm_rss_mb() {
  ps -eo rss,comm 2>/dev/null | grep -i java | awk '{s+=$1} END {printf "%d", s/1024+0}'
}
# "Heavy" = a forked test/elaboration JVM, not an idle sbt server. 1.5 GB RSS separates
# them cleanly on this host.
heavy_jvms() {
  ps -eo rss,comm 2>/dev/null | grep -i java | awk '$1 > 1572864 {n++} END {printf "%d", n+0}'
}
avail_mb() { free -m | awk '/^Mem:/{print $7}'; }

wait_for_room() {
  local waited=0
  while [ "$(avail_mb)" -lt "$NEED_FREE_MB" ] || [ "$(heavy_jvms)" -gt "$MAX_HEAVY_JVMS" ]; do
    if [ $((waited % 300)) -eq 0 ]; then
      echo "[gate] waiting for room: $(avail_mb) MB available (want >= ${NEED_FREE_MB})," \
           "$(heavy_jvms) heavy JVMs (want <= ${MAX_HEAVY_JVMS}), $(jvm_rss_mb) MB JVM RSS total"
    fi
    sleep 30
    waited=$((waited + 30))
  done
  echo "[gate] room available: $(avail_mb) MB free, $(heavy_jvms) heavy JVMs, $(jvm_rss_mb) MB JVM RSS"
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

  wait_for_room
  echo "[gate] generating the netlist for '${label}'"
  ( cd "$wt" && "$SBT" -mem 4096 'runMain m68k040.top.GenFullCoreSynthVerilog' ) 2>&1 | tail -3
  local md5
  md5="$(md5sum "$wt/generated/M68kFullCoreSynth.v" | cut -d' ' -f1)"
  echo "[gate] netlist md5 for '${label}': $md5"

  wait_for_room
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
