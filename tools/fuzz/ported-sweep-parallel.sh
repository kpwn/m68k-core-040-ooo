#!/usr/bin/env bash
# Parallel ported-test sweep runner. Unlike ported-sweep.sh (which batches
# SEQUENTIALLY -- a leftover from when the old per-test-recompiling
# ExecuteLockStepSpec made every JVM heavy, ~7.6GB peak; see
# docs' 2026-06-24/06-30 plan notes and the 2026-07-21 lockstep-OOM fix),
# PortedTestRunner has ALWAYS done one lazy Verilator compile per JVM and
# measures under 1GB RSS per run (empirically checked 2026-07-22) -- the
# real bottleneck for the full 763-test corpus is that it's ~48 minutes of
# STRICTLY SEQUENTIAL simulation in one process, leaving the rest of the
# machine's cores idle.
#
# This script partitions the corpus into N shards and runs them CONCURRENTLY,
# each in its own isolated `git worktree` (never `git checkout` in the shared
# tree -- this project's standing operational rule) so each shard gets an
# independent simWorkspace/ directory with zero risk of two Verilator
# compiles racing on the same build path.
#
# usage: tools/fuzz/ported-sweep-parallel.sh [N] [COMMIT]
#   N       number of parallel worktrees/shards (default 4)
#   COMMIT  commit-ish to check out into each worktree (default: current HEAD)
#
# Output: per-shard logs in ported_logs_parallel/, combined PASS/FAIL summary
# and a merged sorted fail-name list at ported_logs_parallel/all_fails.txt.
set -u
N=${1:-4}
COMMIT=${2:-$(git rev-parse HEAD)}
SBT=${SBT:-$HOME/sbt/bin/sbt}
ASMDIR=${ASMDIR:-src/test/resources/m68kooo-ported-tests/asm}
LOGDIR=ported_logs_parallel
WORKROOT=$(mktemp -d /tmp/ported-sweep-parallel.XXXXXX)
cleanup() {
  for i in $(seq 0 $((N - 1))); do
    git worktree remove --force "$WORKROOT/shard$i" 2>/dev/null
  done
  rm -rf "$WORKROOT"
  git worktree prune 2>/dev/null
}
trap cleanup EXIT

mkdir -p "$LOGDIR"
rm -f "$LOGDIR"/shard_*.log "$LOGDIR"/all_fails.txt

mapfile -t names < <(ls "$ASMDIR"/*.s | xargs -n1 basename | sed 's/\.s$//' | sort)
total=${#names[@]}
echo "[ported-sweep-parallel] $total tests, $N shards, commit=$COMMIT"

# Create N worktrees up front (sequential -- git worktree add on the shared
# repo is itself fast and not worth parallelizing, and doing this part
# sequentially avoids any git-internal lock contention).
declare -a WTDIRS
for i in $(seq 0 $((N - 1))); do
  wt="$WORKROOT/shard$i"
  git worktree add --detach "$wt" "$COMMIT" >"$LOGDIR/worktree_setup_$i.log" 2>&1
  WTDIRS[$i]="$wt"
done
echo "[ported-sweep-parallel] $N worktrees ready"

# Partition test names round-robin across shards (keeps shard sizes even
# regardless of $total % N).
declare -a SHARD_DIRS
for i in $(seq 0 $((N - 1))); do
  sd="$WORKROOT/asm_shard$i"
  mkdir -p "$sd"
  SHARD_DIRS[$i]="$sd"
done
idx=0
for name in "${names[@]}"; do
  shard=$((idx % N))
  cp "$ASMDIR/$name.s" "${SHARD_DIRS[$shard]}/"
  [ -f "$ASMDIR/$name.timeout" ] && cp "$ASMDIR/$name.timeout" "${SHARD_DIRS[$shard]}/"
  idx=$((idx + 1))
done

start=$(date +%s)
declare -a PIDS
for i in $(seq 0 $((N - 1))); do
  n=$(ls "${SHARD_DIRS[$i]}"/*.s 2>/dev/null | wc -l)
  echo "[ported-sweep-parallel] shard $i: $n tests, worktree ${WTDIRS[$i]}"
  (
    cd "${WTDIRS[$i]}"
    PORTED_TEST_DIR="${SHARD_DIRS[$i]}" "$SBT" "testOnly m68k040.fuzz.PortedM68kOooSpec"
  ) >"$LOGDIR/shard_$i.log" 2>&1 &
  PIDS[$i]=$!
done

fails=0
for i in $(seq 0 $((N - 1))); do
  wait "${PIDS[$i]}" || fails=$((fails + 1))
done
end=$(date +%s)

echo
echo "[ported-sweep-parallel] ===== SUMMARY ===== (wall: $((end - start))s)"
for i in $(seq 0 $((N - 1))); do
  grep -h "Tests:" "$LOGDIR/shard_$i.log" 2>/dev/null | sed "s/^/shard $i: /"
done
grep -hE "^\[info\] - ported:.*\*\*\* FAILED \*\*\*" "$LOGDIR"/shard_*.log \
  | sed -E 's/^\[info\] - ported: //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u -o "$LOGDIR/all_fails.txt"
echo "[ported-sweep-parallel] merged fail count: $(wc -l < "$LOGDIR/all_fails.txt")"
echo "[ported-sweep-parallel] shards with a nonzero sbt exit: $fails"
echo "[ported-sweep-parallel] fail list: $LOGDIR/all_fails.txt"
