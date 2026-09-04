#!/bin/bash
# Part 135 postroute synth gate: the RTE/committedCcr fix in ExceptionUnit.scala.
#
# The fix: RTE restores the CCR into the flags PRF but never resynced
# RobPlugin.committedCcr, the shadow the NEXT exception entry stacks from.  A
# second interrupt at the same flag-consuming instruction therefore stacked the
# handler's flags.  Two lines in R_REDIR pulsing the existing obsSetCcr5 port.
# Verified 18/18 (baseline 4 fail), ExecuteLockStepSpec 505/505, fuzz still 3.
#
# src/main IS changed (41+/3- in ExceptionUnit.scala), so a gate is required
# before this can merge.
#
# ADMISSION RULE -- learned the hard way today.  Do NOT gate on *total* JVM RSS:
# idle sbt servers sit resident at 3-4 GB doing no work, which makes a "total
# under 2 GB" condition unsatisfiable and blocked a sibling's gate for hours
# through an idle host.  The real constraints are available memory (a KU5P
# full_impl peaks at 12-15 GB) and the count of genuinely-heavy forked test JVMs.
set -u
cd "$(dirname "$0")" || exit 1

LOG=run_p135_gate.log
exec >>"$LOG" 2>&1
echo "=== P135 gate wrapper start $(date -Is) ==="
echo "=== branch $(git rev-parse --short HEAD) ==="

need_room() {
    local avail heavy
    avail=$(free -m | awk '/^Mem:/{print $7}')
    heavy=$(ps -eo rss,comm | awk '$2 ~ /java/ && $1 > 1572864' | wc -l)
    [ "$avail" -ge 17000 ] && [ "$heavy" -le 2 ]
}

while :; do
    if need_room && flock -n /var/tmp/m68k-ooo-vivado.lock -c true 2>/dev/null; then
        echo "[$(date -Is)] room + mutex free; launching gate"
        break
    fi
    echo "[$(date -Is)] waiting: avail=$(free -m | awk '/^Mem:/{print $7}')MB heavy_jvm=$(ps -eo rss,comm | awk '$2 ~ /java/ && $1 > 1572864' | wc -l) mutex=$(flock -n /var/tmp/m68k-ooo-vivado.lock -c true 2>/dev/null && echo free || echo held)"
    sleep 180
done

# Queue on the real mutex (blocking, never forced).  12h ceiling.
flock -w 43200 /var/tmp/m68k-ooo-vivado.lock bash -c '
  set -u
  cd "$1" || exit 1
  echo "[gate] mutex acquired $(date -Is)"
  vivado -mode batch -nojournal -source synth/impl_FullCore.tcl > synth/p135_gate.out 2>&1
  echo "[gate] vivado rc=$? at $(date -Is)"
' _ "$PWD"
rc=$?

echo "=== P135 gate end $(date -Is) rc=$rc ==="
echo "--- clk-domain result (quote THIS, not the timing_summary headline row) ---"
grep -E "SIGNOFF_200MHZ_(WNS_NS|RESULT)" synth/p135_gate.out 2>/dev/null | tail -4
grep -E "^Setup :|^Hold  :" synth/p135_gate.out 2>/dev/null | tail -2
exit $rc
