#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────────
# Part 135 postroute sign-off gate — SELF-ADMITTING and QUEUED.
#
# The change under gate is `investigate/p135-m1-throwaway-frame`'s two-line edit to
# ExceptionUnit's R_REDIR (pulse the existing obsSetCcr5 port on RTE). The Vivado
# mutex was held by another agent's KU5P gate for this entire session, so this script
# exists to run the gate WHENEVER the slot frees, with nobody watching.
#
# Launch detached (plain `nohup ... &` still dies with the parent shell):
#     setsid nohup ./synth/p135_gate.sh </dev/null >/dev/null 2>&1 &
#
# THREE ARMS, each an independent lock acquisition so siblings can interleave:
#   fix   — the branch as it stands
#   base  — pristine bb3bca1, same flow, for an apples-to-apples delta
#   ctl   — NET-RENAMING CONTROL, run ONLY if the fix looks adverse. Pristine LOGIC
#           with the fix's exact line COUNT (the two logic lines replaced by two
#           comment lines, everything else byte-identical), so SpinalHDL emits the
#           same line-number-derived net names as the fix arm. Today an apparent
#           8.3 MHz regression elsewhere turned out to be 86% net-renaming churn, so
#           an adverse delta must not be attributed to logic without this arm.
#
# Reports SIGNOFF_200MHZ_WNS_NS / SIGNOFF_200MHZ_RESULT — the `clk`-domain verdict
# re-derived at a real 5.000ns — NOT the timing_summary.rpt headline row.
# ─────────────────────────────────────────────────────────────────────────────────
set -u

S=/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad
OUT=$S/p135_gate.log
LOCK=/var/tmp/m68k-ooo-vivado.lock
ADVERSE_NS=0.050        # fix-vs-base WNS regression that triggers the control arm

log() { echo "[$(date '+%F %T')] $*" >> "$OUT"; }

# ── Admission ────────────────────────────────────────────────────────────────────
# >= 17 GB available AND <= 2 FORKED TEST JVMs above 1.5 GB.
#
# Count only forked test JVMs: sbt launches those with an `@.../sbt-args<N>` argfile.
# Idle sbt SERVERS (-Dsbt.script=) sit at 3-4 GB doing nothing and must NOT count —
# gating on TOTAL JVM RSS is unsatisfiable on this host and cost a sibling hours.
# `[s]bt-args` is a character class so this awk's own ps line cannot match itself;
# never use a bare `pgrep -f` / `pkill -f` pattern here, both self-match.
#
# Also refuses to start while anyone else holds the Vivado mutex, so the sbt Verilog
# generation below never runs a JVM during someone else's gate.
wait_for_slot() {
  local avail jvms
  while true; do
    avail=$(free -m | awk '/^Mem:/{print $7}')
    jvms=$(ps -eo rss,args | awk '$1 > 1572864 && /[s]bt-args/ {n++} END {print n+0}')
    if [ "${avail:-0}" -ge 17408 ] && [ "${jvms:-9}" -le 2 ] \
       && flock -n "$LOCK" -c true 2>/dev/null; then
      log "admitted: ${avail}MB available, ${jvms} forked test JVM(s), mutex free"
      return 0
    fi
    log "waiting: ${avail}MB available, ${jvms} forked test JVM(s)"
    sleep 120
  done
}

# ── One arm ──────────────────────────────────────────────────────────────────────
run_arm() {
  local name=$1 wt=$2
  log "=== ARM $name : $wt"
  wait_for_slot
  cd "$wt" || { log "ARM $name: worktree missing"; return 1; }

  # The gate tcl does `read_verilog generated/M68kFullCoreSynth.v`. That file is
  # emitted by GenFullCoreSynthVerilog -- NOT by GenVerilog, which emits M68kCore.v
  # and exits 0 in about a second. The first run of this script used GenVerilog, so
  # every arm sailed past a zero exit code with no netlist on disk and Vivado died at
  # read_verilog. Hence the explicit target below and the hard assertion after it.
  log "ARM $name: generating Verilog (GenFullCoreSynthVerilog)"
  SBT_OPTS="-Xmx8G -Djava.io.tmpdir=/home/qwertyoruiop/tmp" \
    sbt -batch "runMain m68k040.top.GenFullCoreSynthVerilog" \
    >> "$S/p135_gate_${name}_gen.log" 2>&1
  local genrc=$?
  if [ $genrc -ne 0 ]; then log "ARM $name: ABORT -- GEN FAILED rc=$genrc"; return 1; fi

  # STRUCTURAL GUARD: never take the mutex on a netlist that is missing or stub-sized.
  # A real M68kFullCoreSynth.v is tens of MB; anything under 1 MB is a failed build.
  local nv=generated/M68kFullCoreSynth.v nbytes
  if [ ! -s "$nv" ]; then
    log "ARM $name: ABORT -- $nv is missing or empty after generation (mutex NOT taken)"
    return 1
  fi
  nbytes=$(stat -c %s "$nv")
  if [ "$nbytes" -lt 1000000 ]; then
    log "ARM $name: ABORT -- $nv is only ${nbytes} bytes, that is not a full-core netlist"
    return 1
  fi
  log "ARM $name: netlist ${nbytes} bytes, md5 $(md5sum "$nv" | cut -d' ' -f1)"

  # Re-check admission: the sbt JVM above may still be winding down.
  wait_for_slot
  log "ARM $name: acquiring the Vivado mutex (blocking)"
  flock "$LOCK" \
    vivado -mode batch -nojournal \
           -log "$S/p135_gate_${name}_vivado.log" \
           -source synth/impl_FullCore.tcl \
    >> "$S/p135_gate_${name}_impl.log" 2>&1
  local rc=$?
  log "ARM $name: vivado rc=$rc"
  if [ $rc -ne 0 ]; then
    log "ARM $name: ABORT -- Vivado exited non-zero; this arm has NO usable result"
    return 1
  fi
  # Anchor to line start: Vivado echoes the sourced tcl, and impl_FullCore.tcl mentions
  # SIGNOFF_200MHZ_* inside `#` commentary. An unanchored grep matches the documentation
  # as readily as the result -- that is where the first run's "#ns" came from.
  grep -hE "^(SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_|REUSE_SYNTH_DCP)" \
       "$S/p135_gate_${name}_impl.log" 2>/dev/null | sed "s/^/ARM $name  /" >> "$OUT"
  return 0
}

# Echo the arm's clk-domain sign-off WNS, but ONLY if it is a real number.
# Anchored to line start so commented mentions of SIGNOFF_200MHZ_* in the sourced tcl
# cannot be scraped as a result, and validated as numeric so a non-number can never
# reach the comparison below. Emits nothing at all when there is no genuine result.
wns_of() {
  local v
  v=$(grep -hE "^SIGNOFF_200MHZ_WNS_NS[[:space:]]" "$S/p135_gate_${1}_impl.log" 2>/dev/null \
      | tail -1 | awk '{print $2}')
  case "$v" in
    ''|*[!0-9.+-]*) return 1 ;;   # empty, or contains a non-numeric character
  esac
  awk -v x="$v" 'BEGIN{ if (x+0 == x || x ~ /^[+-]?[0-9]*\.?[0-9]+$/) exit 0; exit 1 }' \
    || return 1
  echo "$v"
}

verdict_of() {
  grep -hE "^SIGNOFF_200MHZ_RESULT[[:space:]]" "$S/p135_gate_${1}_impl.log" 2>/dev/null \
    | tail -1 | awk '{print $2}'
}

# ── Build the net-renaming control arm from the fix arm, reproducibly ────────────
# Pristine LOGIC, the fix's exact line COUNT: the two logic lines become two comment
# lines and every other byte is identical, so SpinalHDL's line-number-derived net
# names match the fix arm and the only difference left is the logic itself.
# Built here rather than assumed to exist, so this script is self-contained.
build_ctl_arm() {
  local ctl=$S/wt-p135-ctl
  local f=src/main/scala/m68k040/exception/ExceptionUnit.scala
  if [ ! -d "$ctl" ]; then
    git -C "$S/wt-p135" worktree add --detach "$ctl" bb3bca1 >> "$OUT" 2>&1 || return 1
  fi
  cp "$S/wt-p135/$f" "$ctl/$f" || return 1
  python3 - "$ctl/$f" <<'PY' >> "$OUT" 2>&1 || return 1
import sys
p = sys.argv[1]
s = open(p).read()
old = "        obsSetCcr5Valid := True\n        obsSetCcr5      := popSr(4 downto 0)"
new = ("        // NET-RENAMING CONTROL: logic removed, line count preserved.\n"
       "        // (was: obsSetCcr5Valid := True / obsSetCcr5 := popSr(4 downto 0))")
assert s.count(old) == 1, "control patch anchor not unique: %d" % s.count(old)
open(p, "w").write(s.replace(old, new))
print("control arm patched")
PY
  local a b
  a=$(wc -l < "$S/wt-p135/$f"); b=$(wc -l < "$ctl/$f")
  [ "$a" = "$b" ] || { log "CONTROL ARM line counts differ ($a vs $b) -- aborting control"; return 1; }
  log "control arm built: $b lines, identical to the fix arm but for the two logic lines"
  return 0
}

# ── Sequence ─────────────────────────────────────────────────────────────────────
: > "$OUT"
log "Part 135 postroute gate queued. Mutex: $LOCK"
log "fix=$S/wt-p135  base=$S/wt-p135-base  ctl=$S/wt-p135-ctl"

run_arm fix  "$S/wt-p135"; FIX_OK=$?
run_arm base "$S/wt-p135-base"; BASE_OK=$?

FIX=$(wns_of fix)   || FIX=""
BASE=$(wns_of base) || BASE=""

# NO VERDICT WITHOUT NUMBERS. The first run of this script compared "#" against "#",
# found them equal, and logged "NOT adverse" -- a confident green from a run that never
# happened. A missing or unparsable WNS is now a hard FAILURE, never a pass.
if [ -z "$FIX" ] || [ -z "$BASE" ]; then
  log "GATE FAILED -- no usable sign-off number."
  log "  fix  arm rc=$FIX_OK  SIGNOFF_200MHZ_WNS_NS='${FIX:-<none parsed>}'"
  log "  base arm rc=$BASE_OK SIGNOFF_200MHZ_WNS_NS='${BASE:-<none parsed>}'"
  log "  DO NOT read this as a pass. Inspect p135_gate_{fix,base}_{gen,impl}.log."
  log "Part 135 gate DONE (FAILED)"
  echo "P135_GATE_FAILED_NO_RESULT" >> "$OUT"
  exit 1
fi

log "SUMMARY fix WNS=${FIX}ns ($(verdict_of fix))  base WNS=${BASE}ns ($(verdict_of base))"

if true; then
  ADVERSE=$(awk -v f="$FIX" -v b="$BASE" -v t="$ADVERSE_NS" \
              'BEGIN{print ((b-f) > t) ? 1 : 0}')
  if [ "$ADVERSE" = "1" ]; then
    log "fix is worse than base by more than ${ADVERSE_NS}ns -> running the NET-RENAMING CONTROL"
    if build_ctl_arm; then
      run_arm ctl "$S/wt-p135-ctl"
      CTL=$(wns_of ctl)
      log "SUMMARY ctl WNS=${CTL:-?}ns"
      log "ATTRIBUTION: base->ctl is pure net-renaming churn; ctl->fix is the logic."
    else
      log "control arm could not be built -- do NOT attribute the delta to logic without it"
    fi
  else
    log "fix is NOT adverse vs base -> control arm not needed, mutex released."
  fi
fi

log "Part 135 gate DONE"
echo "P135_GATE_ALL_DONE" >> "$OUT"
