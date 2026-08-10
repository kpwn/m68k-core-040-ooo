#!/usr/bin/env bash
# Extract the full post-route gate report shape from floorplan_ab.sh run directories
# (or from an archived checkpoint directory, which has the same file layout).
# Usage: synth/floorplan_ab_report.sh <dir> [<dir> ...]
#   <dir> may be   <runroot>/<tag>            (an A/B run: reads ../<tag>.out for the log)
#             or   synth/archive/<checkpoint> (a pinned archive: log is optional)
set -uo pipefail
for D in "$@"; do
  T="$D/synth/fullcore_route_timing.rpt"; [ -f "$T" ] || T="$D/fullcore_route_timing.rpt"
  U="$D/synth/fullcore_route_util.rpt";   [ -f "$U" ] || U="$D/fullcore_route_util.rpt"
  L="$(dirname "$D")/$(basename "$D").out"; [ -f "$L" ] || L=/dev/null
  echo "=================================================================="
  echo "RUN $(basename "$D")"
  grep -ahE '^(FLOORPLAN_MODE|IMPL_STRATEGY|NETLIST_MD5|POSTSYNTH_FULLCORE_WNS_NS|FLOORPLAN pb_|POSTROUTE_FULLCORE_(WNS_NS|RESULT))' "$L" 2>/dev/null | sed 's/^/  /'
  if [ -f "$T" ]; then
    awk '/^ *WNS\(ns\)/{getline; getline; \
      printf "  WNS %s ns   FMax %.3f MHz   TNS %s   TNS-failing %s / %s   WHS %s   THS-failing %s   WPWS %s   TPWS-failing %s\n", \
        $1, 1000.0/(4.000-$1), $2, $3, $4, $5, $7, $9, $11; exit}' "$T"
    echo "  TOP PATH:"
    sed -n '/Slack (VIOLATED)/,/^ *Data Path Delay/p' "$T" | \
      grep -aE 'Slack|Source:|Destination:|Path Group|Data Path Delay|Logic Levels' | head -8 | sed 's/^/    /'
    sed -n '/Slack (VIOLATED)/,/Destination:/p' "$T" | grep -aA1 -E 'Source:|Destination:' | grep -aE 'reg|/' | head -4 | sed 's/^/    /'
  fi
  if [ -f "$U" ]; then
    grep -ahE '^\| (CLB LUTs|CLB Registers|Block RAM Tile|DSPs|CARRY8|F7 Muxes|F8 Muxes|LUT as Memory) +\|' "$U" | sed 's/^/  AREA /'
  fi
  for P in "$D"/synth/fullcore_pb_*_util.rpt "$D"/fullcore_pb_*_util.rpt; do
    [ -f "$P" ] || continue
    echo "  PBLOCK $(basename "$P" | sed 's/fullcore_//;s/_util.rpt//'):"
    grep -ahE '^\| (CLB LUTs|CLB Registers) +\|' "$P" | sed 's/^/    /'
  done
  C="$D/synth/fullcore_congestion.rpt"; [ -f "$C" ] || C="$D/fullcore_congestion.rpt"
  if [ -f "$C" ]; then
    echo "  CONGESTION: $(grep -ac 'No congestion windows are found above level 5' "$C" 2>/dev/null || echo 0)/1 placer-clean, $(grep -ac 'No effective congestion windows are found above level 5' "$C" 2>/dev/null || echo 0)/1 router-clean"
    grep -ahE '^\| (North|South|East|West) +\|' "$C" | sed 's/^/    /' | head -8
  fi
done
