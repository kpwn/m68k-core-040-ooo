#!/bin/bash
# Post-route sign-off gate for the I-side cache-inhibited SPECULATION gate.
#
# Arms (each is one full POSTROUTE_ROUNDS=9 impl run, under the shared Vivado mutex):
#   base  = pristine ca4b901          (worktree $BASE_WT)  -- the reference
#   fix   = fix/ispec-inhibited-fetch (worktree $FIX_WT)
#   ctrl  = the NET-RENAMING CONTROL  (worktree $CTRL_WT): the FIX tree with the gate
#           forced inactive (`nonSpecFetch := True`), so every net name and every
#           SpinalHDL line number matches FIX while the added `cmdPort.ready` term
#           constant-folds away. Run ONLY if FIX shows an adverse delta against BASE,
#           to separate line-number churn from the real cost of the added term.
#
# Pass the arms to run as arguments, e.g.  ./synth/run_ispec_gate.sh fix
#
# Committed on purpose: three queued experiments were silently lost on 2026-09-04
# because their launchers only ever existed in a shell.
set -u
SCRATCH=/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad
BASE_WT=$SCRATCH/wt-ibase
FIX_WT=$SCRATCH/wt-ispec
CTRL_WT=$SCRATCH/wt-ictrl
LOCK=/var/tmp/m68k-ooo-vivado.lock
OUT=/home/qwertyoruiop/tmp

export SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"

wt_for() { case "$1" in base) echo "$BASE_WT" ;; fix) echo "$FIX_WT" ;; ctrl) echo "$CTRL_WT" ;; esac; }

gen() {  # $1 = worktree
  cd "$1" || exit 1
  sbt -batch 'runMain m68k040.top.GenFullCoreSynthVerilog' > "$OUT/gen_$(basename "$1").log" 2>&1
  md5sum generated/M68kFullCoreSynth.v
}

wait_for_mem() {  # the standing admission rule for starting a Vivado build
  while true; do
    avail=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
    jvms=$(ps -eo rss,comm --no-headers | awk '$2=="java" && $1>1572864' | wc -l)
    [ "$avail" -ge 17 ] && [ "$jvms" -le 2 ] && break
    sleep 60
  done
}

arm() {  # $1 = tag
  local wt; wt=$(wt_for "$1")
  cd "$wt" || exit 1
  wait_for_mem
  # NEVER force the mutex: queue behind whoever holds it.
  flock "$LOCK" env POSTROUTE_ROUNDS=9 \
    vivado -mode batch -nojournal -log "synth/vivado_$1.log" \
      -source synth/impl_FullCore.tcl > "$OUT/gate_$1.out" 2>&1
  echo "ARM $1 EXIT=$?" >> "$OUT/gate_$1.out"
}

for a in "$@"; do
  echo "=== $a netlist md5 $(gen "$(wt_for "$a")") ==="
done
for a in "$@"; do arm "$a"; done

echo "=== done ==="
for a in "$@"; do
  grep -hE "^(SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_|ARM )" "$OUT/gate_$a.out"
done
