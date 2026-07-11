#!/usr/bin/env bash
# Fuzz sweep runner — honors the project's JVM discipline: batches of seeds per
# JVM, batches run SINGLY (the forked test JVM peaks ~7.6GB regardless of
# JAVA_OPTS; never share a JVM with the directed ExecuteLockStepSpec).
#
# usage: tools/fuzz/sweep.sh [START] [COUNT] [BATCH] [BLOCKS]
#   START  first seed              (default 0)
#   COUNT  total seeds to run      (default 100)
#   BATCH  seeds per JVM/sbt run   (default 25; one Verilator compile per batch)
#   BLOCKS body template blocks per program (default 20 -> ~50-110 instrs)
#
# Output: per-batch logs in fuzz_logs/, a combined summary at the end.
set -u
START=${1:-0}
COUNT=${2:-100}
BATCH=${3:-25}
BLOCKS=${4:-20}
SBT=${SBT:-$HOME/sbt/bin/sbt}
LOGDIR=${LOGDIR:-fuzz_logs}
mkdir -p "$LOGDIR"

END=$((START + COUNT))
echo "[sweep] seeds [$START,$END) batch=$BATCH blocks=$BLOCKS"
fails=0
for ((s = START; s < END; s += BATCH)); do
  n=$BATCH
  if ((s + n > END)); then n=$((END - s)); fi
  log="$LOGDIR/fuzz_${s}_$((s + n)).log"
  echo "[sweep] batch seeds [$s,$((s + n))) -> $log"
  FUZZ_SEED_START=$s FUZZ_SEED_COUNT=$n FUZZ_BLOCKS=$BLOCKS \
    JAVA_OPTS=-Xmx10g timeout 1800 "$SBT" 'testOnly m68k040.fuzz.FuzzLockStepSpec' \
    >"$log" 2>&1
  rc=$?
  if ((rc != 0)); then fails=$((fails + 1)); fi
  grep -E '^\[fuzz\] seed=' "$log" || echo "[sweep]   (no per-seed lines — batch crashed? rc=$rc)"
done

echo
echo "[sweep] ===== SUMMARY ====="
cat "$LOGDIR"/fuzz_*.log 2>/dev/null | grep -E '^\[fuzz\] seed=' | sort -t= -k2 -n | uniq
echo "[sweep] divergence reports:"
grep -l 'FUZZ DIVERGENCE' "$LOGDIR"/fuzz_*.log 2>/dev/null || echo "[sweep]   none"
echo "[sweep] failed batches: $fails"
