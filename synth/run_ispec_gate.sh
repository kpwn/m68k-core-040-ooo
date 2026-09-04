#!/bin/bash
# Post-route sign-off gate for the I-side cache-inhibited speculation fix.
#
# Two arms, run BACK TO BACK under the shared Vivado mutex so they never contend:
#   BASE = pristine ca4b901   (worktree $BASE_WT)
#   FIX  = d615b8f            (worktree $FIX_WT)
# A third CTRL arm (the net-renaming control: the FIX tree with
# `SpeculativeFetchGate` forced to `nonSpecFetch := True`, so every net name and
# every SpinalHDL line number is identical to FIX while the added `cmdPort.ready`
# term constant-folds away) is only run if FIX regresses against BASE.
#
# Committed on purpose: three queued experiments were silently lost on 2026-09-04
# because their launchers only ever existed in a shell.
set -u
BASE_WT=/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad/wt-ibase
FIX_WT=/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad/wt-ispec
LOCK=/var/tmp/m68k-ooo-vivado.lock
OUT=/home/qwertyoruiop/tmp

export SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"

gen() {  # $1 = worktree
  cd "$1" || exit 1
  sbt -batch 'runMain m68k040.top.GenFullCoreSynthVerilog' > "$OUT/gen_$(basename "$1").log" 2>&1
  md5sum generated/M68kFullCoreSynth.v
}

wait_for_mem() {  # do not start a Vivado build under the standing admission rule
  while true; do
    avail=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
    jvms=$(ps -eo rss,comm --no-headers | awk '$2=="java" && $1>1572864' | wc -l)
    [ "$avail" -ge 17 ] && [ "$jvms" -le 2 ] && break
    sleep 60
  done
}

arm() {  # $1 = worktree, $2 = tag
  cd "$1" || exit 1
  wait_for_mem
  # NEVER force the mutex: queue behind whoever holds it.
  flock "$LOCK" env POSTROUTE_ROUNDS=9 \
    vivado -mode batch -nojournal -log "synth/vivado_$2.log" \
      -source synth/impl_FullCore.tcl > "$OUT/gate_$2.out" 2>&1
  echo "ARM $2 EXIT=$?" >> "$OUT/gate_$2.out"
}

echo "=== generating netlists ==="
echo "BASE md5 $(gen $BASE_WT)"
echo "FIX  md5 $(gen $FIX_WT)"

arm "$BASE_WT" base
arm "$FIX_WT"  fix

echo "=== done ==="
grep -hE "SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_" "$OUT/gate_base.out" "$OUT/gate_fix.out" | grep -v '^#'
