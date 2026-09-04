#!/bin/bash
# Sweep candidate WEAKER predicates for `SpeculativeFetchGate.drainedRaw` and record whether
# the sibling `spec-mmio I-side` trio still holds. Results and the reasoning are in
# docs/superpowers/specs/2026-09-04-speculative-inhibited-fetch-gate-cost.md §2.
#
# A variant that FAILS `THE RULE` is PROVEN PERMISSIVE — it issued a real wrong-path 64-byte
# burst into CM=10 space. A variant that PASSES is only *not disproven by this one probe*,
# which is NOT a licence to adopt it: see §2.2 for the specific blind spot (the probe cannot
# detect a permissive removal of the frontend quiet group).
#
# Destructive: rewrites the `val drainedRaw = …` line in place, then restores it with
# `git checkout --`. Run it on a throwaway branch, never on a tree with uncommitted work.
#
# Committed on purpose — the fix's own launcher comment records that three queued
# experiments were silently lost on 2026-09-04 because they only ever existed in a shell.
set -u
WT="$(cd "$(dirname "$0")" && pwd)"
GATE=$WT/src/main/scala/m68k040/top/SpeculativeFetchGate.scala
LOG=${1:-/home/qwertyoruiop/tmp/predicate_sweep.log}
export SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"

cd "$WT" || exit 1
[ "$(git status --porcelain -- "$GATE" | wc -l)" = "0" ] || {
  echo "SWEEP ABORT: $GATE already modified; refusing to clobber"; exit 1; }

run_variant() {  # $1 = tag, $2 = Scala expression for drainedRaw
  local tag="$1" expr="$2"
  python3 - "$GATE" "$expr" <<'PY' || return 1
import sys,re
p,e=sys.argv[1],sys.argv[2]
s=open(p).read()
s2,n=re.subn(r'(?m)^    val drainedRaw = .*$', '    val drainedRaw = '+e, s, count=1)
assert n==1, "drainedRaw anchor not found"
open(p,'w').write(s2)
PY
  echo "### VARIANT $tag  drainedRaw = $expr"
  sbt -batch 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "spec-mmio I-side"' 2>&1 \
    | grep -E "^\[spec-mmio-|Tests: |\*\*\* FAILED|Tests unsuccessful|Total time"
  echo "### VARIANT $tag END"
}

{
  echo "=== PREDICATE SWEEP start $(date -Is) HEAD=$(git rev-parse --short HEAD) ==="
  run_variant robonly       "robQuiet"
  run_variant frontend_rob  "frontendQuiet && robQuiet"
  run_variant decrename_rob "decodeQuiet && renameQuiet && robQuiet"
  run_variant frontend_only "frontendQuiet"
  run_variant full          "frontendQuiet && decodeQuiet && renameQuiet && robQuiet"
  echo "=== PREDICATE SWEEP DONE $(date -Is) ==="
} > "$LOG" 2>&1

git checkout -- "$GATE"
echo "=== RESTORED $(git rev-parse --short HEAD) dirty=$(git status --porcelain | wc -l) ===" >> "$LOG"
