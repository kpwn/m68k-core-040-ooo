#!/bin/bash
# The BEFORE arm for the I-side speculation gate, in one command: force
# `ic.logic.nonSpecFetch := True` so the gate is inert (behaviourally the pre-fix RTL) and
# re-run the three sibling `spec-mmio I-side` tests. `THE RULE` must FAIL with at least one
# wrong-path 64-byte burst into the CM=10 window, and CONTROL-POSITIVE must stay at 5 window
# ARs — if the control-positive moves, the frontend stopped running ahead and the arm proves
# nothing. Results in
# docs/superpowers/specs/2026-09-04-speculative-inhibited-fetch-gate-cost.md §2.3.
#
# Destructive: rewrites the final assignment in place, then restores with `git checkout --`.
set -u
WT="$(cd "$(dirname "$0")" && pwd)"
GATE=$WT/src/main/scala/m68k040/top/SpeculativeFetchGate.scala
LOG=${1:-/home/qwertyoruiop/tmp/gateoff.log}
export SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"

cd "$WT" || exit 1
[ "$(git status --porcelain -- "$GATE" | wc -l)" = "0" ] || {
  echo "GATEOFF ABORT: $GATE already modified; refusing to clobber"; exit 1; }

{
  echo "=== GATEOFF start $(date -Is) HEAD=$(git rev-parse --short HEAD) ==="
  python3 - "$GATE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='    ic.logic.nonSpecFetch := drainedQ && (fl.ringCount === 0)'
new=('    ic.logic.nonSpecFetch := True  // BEFORE ARM: gate inert == pre-fix behaviour\n'
     '    val _u = drainedQ && (fl.ringCount === 0)')
assert old in s, "anchor not found"
open(p,'w').write(s.replace(old,new,1))
PY
  if [ $? -ne 0 ]; then echo "GATEOFF ABORT: rewrite failed"; else
    sbt -batch 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "spec-mmio I-side"' 2>&1 \
      | grep -E "^\[spec-mmio-|Tests: |\*\*\* FAILED|Total time"
  fi
  echo "=== GATEOFF DONE $(date -Is) ==="
} > "$LOG" 2>&1

git checkout -- "$GATE"
echo "=== RESTORED $(git rev-parse --short HEAD) dirty=$(git status --porcelain | wc -l) ===" >> "$LOG"
