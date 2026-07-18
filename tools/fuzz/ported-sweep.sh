#!/usr/bin/env bash
# Ported-test sweep runner -- same JVM-batching discipline as
# tools/fuzz/sweep.sh: one compile per JVM (PortedTestRunner.compiled is
# lazy+shared), batches run singly. Batches by staging a directory SUBSET
# (PORTED_TEST_DIR) rather than a ScalaTest -z filter, since -z is a
# substring match, not an OR-of-names matcher.
#
# usage: tools/fuzz/ported-sweep.sh [BATCH]
#   BATCH  test files per JVM/sbt run (default 100)
set -u
BATCH=${1:-100}
SBT=${SBT:-$HOME/sbt/bin/sbt}
LOGDIR=${LOGDIR:-ported_logs}
ASMDIR=src/test/resources/m68kooo-ported-tests/asm
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT
mkdir -p "$LOGDIR"

mapfile -t names < <(ls "$ASMDIR"/*.s | xargs -n1 basename | sed 's/\.s$//' | sort)
total=${#names[@]}
echo "[ported-sweep] $total tests, batch=$BATCH"

fails=0
for ((i = 0; i < total; i += BATCH)); do
  batch_dir="$SCRATCH/batch_$i"
  mkdir -p "$batch_dir"
  for name in "${names[@]:i:BATCH}"; do
    cp "$ASMDIR/$name.s" "$batch_dir/"
    [ -f "$ASMDIR/$name.timeout" ] && cp "$ASMDIR/$name.timeout" "$batch_dir/"
  done
  n=$(ls "$batch_dir"/*.s | wc -l)
  log="$LOGDIR/batch_${i}.log"
  echo "[ported-sweep] batch [$i,$((i + n))) -> $log"
  PORTED_TEST_DIR="$batch_dir" timeout 1800 "$SBT" "testOnly m68k040.fuzz.PortedM68kOooSpec" \
    >"$log" 2>&1
  rc=$?
  if ((rc != 0)); then fails=$((fails + 1)); fi
  rm -rf "$batch_dir"
done

echo
echo "[ported-sweep] ===== SUMMARY ====="
grep -h "Tests:" "$LOGDIR"/batch_*.log 2>/dev/null
echo "[ported-sweep] batches with failures: $fails"
