#!/bin/bash
# Full re-verification of the tree it lives in, one JVM at a time so the standing admission
# rule (<= 2 forked test JVMs above 1.5 GB) is respected. Each stage prints a tagged banner
# so a silent death is distinguishable from silence, and the rc is captured from the sbt
# process rather than from the grep it is piped into.
#
# Results for `fix/ispec-inhibited-fetch` are in
# docs/superpowers/specs/2026-09-04-speculative-inhibited-fetch-gate-cost.md §6.
#
# NOTE (§6.1): `m68k040.ls.*` has a load-sensitive flake population. Under a concurrent
# Vivado build it over-reports by one or two, with the extra failure ROTATING between runs.
# Only an uncontended run's failing NAME SET is meaningful; re-run before believing a delta.
#
# Refuses to run on a dirty tree: a stale edit silently invalidates every number below.
set -u
WT="$(cd "$(dirname "$0")" && pwd)"
LOG=${1:-/home/qwertyoruiop/tmp/reverify.log}
cd "$WT" || { echo "REVERIFY ABORT: no worktree"; exit 1; }

dirty=$(git status --porcelain -- src | wc -l)
head=$(git rev-parse --short HEAD)
if [ "$dirty" != "0" ]; then
  echo "REVERIFY ABORT: src/ not pristine (HEAD=$head dirty=$dirty)" > "$LOG"; exit 1
fi

stage() {  # $1 = tag, $2 = sbt command, $3 = Xmx
  export SBT_OPTS="-Xmx$3 -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"
  echo "### STAGE $1 start $(date -Is)"
  sbt -batch "$2" 2>&1 | grep -E "Tests: |Total time|\*\*\* FAILED|TESTS FAILED|error\]|\[fuzz\]|OutOfMemory|Killed"
  echo "### STAGE $1 rc=${PIPESTATUS[0]} end $(date -Is)"
}

{
  echo "=== REVERIFY start $(date -Is) HEAD=$head pristine ==="
  stage lockstep 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 6G
  stage testfast 'fastTest'                                       6G
  stage ls       'testOnly m68k040.ls.*'                          6G
  stage cache    'testOnly m68k040.cache.*'                       6G
  export FUZZ_SEED_START=0 FUZZ_SEED_COUNT=200 FUZZ_MINIMIZE=0
  stage fuzz200  'testOnly m68k040.fuzz.FuzzLockStepSpec'         4G
  echo "=== REVERIFY DONE $(date -Is) dirty=$(git status --porcelain | wc -l) ==="
} > "$LOG" 2>&1
