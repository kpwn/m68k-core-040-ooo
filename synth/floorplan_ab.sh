#!/usr/bin/env bash
# Same-DCP floorplan A/B runner (handoff section 18).
#
# Re-places and re-routes ONE fixed post-synthesis checkpoint under N different
# FLOORPLAN_MODE settings, each in its own isolated run directory so the runs can
# go in parallel without fighting over synth/fullcore_*.rpt / fullcore_routed.dcp.
# Only the floorplan constraint differs between runs; the netlist is bit-identical,
# which is what makes the comparison a controlled experiment rather than a re-synth.
#
# Usage:
#   synth/floorplan_ab.sh <runroot> <spec> [<spec> ...]
# Each <spec> is  <FLOORPLAN_MODE>[@<IMPL_STRATEGY>]  where FLOORPLAN_MODE joins pblock
# tokens with '+' (e.g. decode+frontend) and IMPL_STRATEGY defaults to `default`.
# Results land in <runroot>/<sanitised-spec>/ ; the summary line of each is
# POSTROUTE_FULLCORE_WNS_NS in <runroot>/<sanitised-spec>.out.
# Report them with synth/floorplan_ab_report.sh <runroot>/<sanitised-spec>.
#
# Each run needs ~6 GB RSS and 11-25 min; check `free -g` before launching more than
# three at once on the shared machine.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNROOT="${1:?usage: floorplan_ab.sh <runroot> <mode>...}"; shift
SYNTH_ARCHIVE="${SYNTH_ARCHIVE:-$REPO/synth/archive/6b246de_ftb_framing_retime_decode}"

mkdir -p "$RUNROOT"
for SPEC in "$@"; do
  MODE="${SPEC%%@*}"
  STRAT="default"; [ "$SPEC" != "$MODE" ] && STRAT="${SPEC#*@}"
  TAG="${SPEC//+/_}"; TAG="${TAG//@/-}"
  D="$RUNROOT/$TAG"
  rm -rf "$D"; mkdir -p "$D/synth" "$D/generated"
  ln -sf "$REPO/generated/M68kFullCoreSynth.v" "$D/generated/M68kFullCoreSynth.v"
  cp "$REPO"/synth/impl_FullCore.tcl "$REPO"/synth/clk.xdc "$REPO"/synth/floorplan_*.xdc "$D/synth/"
  ln -sf "$SYNTH_ARCHIVE/fullcore_synth.dcp" "$D/synth/fullcore_synth.dcp"
  cp "$SYNTH_ARCHIVE/fullcore_synth.md5" "$D/synth/fullcore_synth.md5"
  (
    cd "$D"
    echo "AB_START $SPEC $(date -Is)"
    echo "AB_SYNTH_DCP $SYNTH_ARCHIVE  md5=$(cat synth/fullcore_synth.md5)"
    REUSE_SYNTH_DCP=1 FLOORPLAN_MODE="$MODE" IMPL_STRATEGY="$STRAT" \
      vivado -mode batch -nojournal -nolog -source synth/impl_FullCore.tcl
    echo "AB_END $SPEC $(date -Is)"
  ) > "$RUNROOT/$TAG.out" 2>&1 &
  echo "launched $SPEC (mode=$MODE strategy=$STRAT) -> $RUNROOT/$TAG.out (pid $!)"
done
wait
echo "AB_ALL_DONE"
for SPEC in "$@"; do
  TAG="${SPEC//+/_}"; TAG="${TAG//@/-}"
  printf '%-28s %s\n' "$SPEC" "$(grep -aE 'POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT' "$RUNROOT/$TAG.out" | tr '\n' ' ')"
done
