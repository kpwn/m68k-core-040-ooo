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

  log "ARM $name: generating Verilog"
  SBT_OPTS="-Xmx8G -Djava.io.tmpdir=/home/qwertyoruiop/tmp" \
    sbt -batch "runMain m68k040.top.GenVerilog" >> "$S/p135_gate_${name}_gen.log" 2>&1
  local genrc=$?
  if [ $genrc -ne 0 ]; then log "ARM $name: GEN FAILED rc=$genrc"; return 1; fi
  log "ARM $name: netlist md5 $(md5sum generated/M68kFullCoreSynth.v | cut -d' ' -f1)"

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
  grep -hE "SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_|ACHIEVED_FMAX_MHZ|FMAX_MHZ|REUSE_SYNTH_DCP" \
       "$S/p135_gate_${name}_impl.log" 2>/dev/null | sed "s/^/ARM $name  /" >> "$OUT"
  return 0
}

wns_of() {  # echo the arm's clk-domain sign-off WNS, or empty
  grep -h "SIGNOFF_200MHZ_WNS_NS" "$S/p135_gate_${1}_impl.log" 2>/dev/null \
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

run_arm fix  "$S/wt-p135"
run_arm base "$S/wt-p135-base"

FIX=$(wns_of fix); BASE=$(wns_of base)
log "SUMMARY fix WNS=${FIX:-?}ns  base WNS=${BASE:-?}ns"

if [ -n "${FIX:-}" ] && [ -n "${BASE:-}" ]; then
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
