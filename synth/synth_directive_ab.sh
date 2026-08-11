#!/usr/bin/env bash
# Fresh-synthesis A/B runner (handoff section 26).
#
# Unlike synth/floorplan_ab.sh -- which symlinks ONE frozen post-synthesis DCP so
# only the physical constraint differs -- this runner re-SYNTHESISES the identical
# generated Verilog under N different synth_design recipes, then runs the landed
# physical flow (FLOORPLAN_MODE=decode, IMPL_STRATEGY=postrouteN, 3 rounds) on each.
#
# Why: handoff section 18 step 6 lever 4 and section 19 step 8 both name this as
# the single genuinely untried item on the implementation-recipe lever -- the only
# lever in this campaign that has ever produced a positive result (+18.65 MHz).
# Every physical number published before section 26 descends from one frozen
# default-directive synthesis checkpoint, so the synthesis axis had never varied.
#
# Usage:
#   synth/synth_directive_ab.sh <runroot> <spec> [<spec> ...]
# Each <spec> is one of
#   default                       plain synth_design (the control)
#   dir:<Directive>               -directive <Directive>
#   flat:<mode>                   -flatten_hierarchy <mode>
#   retime                        -retiming
# Results: <runroot>/<tag>.out, summary line POSTROUTE_FULLCORE_WNS_NS.
#
# Each run is a FULL synth+place+route+3 post-route rounds: ~6-9 GB RSS and
# 45-70 min.  Check `free -g` before launching more than two or three at once.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNROOT="${1:?usage: synth_directive_ab.sh <runroot> <spec>...}"; shift

mkdir -p "$RUNROOT"
for SPEC in "$@"; do
  TAG="${SPEC//:/-}"
  D="$RUNROOT/$TAG"
  rm -rf "$D"; mkdir -p "$D/synth" "$D/generated"
  ln -sf "$REPO/generated/M68kFullCoreSynth.v" "$D/generated/M68kFullCoreSynth.v"
  cp "$REPO"/synth/impl_FullCore.tcl "$REPO"/synth/clk.xdc "$REPO"/synth/floorplan_*.xdc "$D/synth/"

  ENVV=()
  case "$SPEC" in
    default)   ;;
    dir:*)     ENVV+=("SYNTH_DIRECTIVE=${SPEC#dir:}") ;;
    flat:*)    ENVV+=("SYNTH_FLATTEN=${SPEC#flat:}") ;;
    retime)    ENVV+=("SYNTH_RETIMING=1") ;;
    *) echo "unknown spec '$SPEC'" >&2; exit 2 ;;
  esac

  (
    cd "$D"
    echo "SDAB_START $SPEC $(date -Is)"
    echo "SDAB_NETLIST_BODY_MD5 $(tail -n +4 generated/M68kFullCoreSynth.v | md5sum | cut -d' ' -f1)"
    # NOTE: no REUSE_SYNTH_DCP -- every run synthesises from scratch, which is the
    # whole point.  FLOORPLAN_MODE / IMPL_STRATEGY are left to impl_FullCore.tcl's
    # own defaults (decode / postrouteN) so the physical recipe is held fixed and
    # only the synthesis recipe varies.
    env "${ENVV[@]}" vivado -mode batch -nojournal -nolog -source synth/impl_FullCore.tcl
    echo "SDAB_END $SPEC $(date -Is)"
  ) > "$RUNROOT/$TAG.out" 2>&1 &
  echo "launched $SPEC -> $RUNROOT/$TAG.out (pid $!)"
done
wait
echo "SDAB_ALL_DONE"
for SPEC in "$@"; do
  TAG="${SPEC//:/-}"
  printf '%-28s %s\n' "$SPEC" \
    "$(grep -aoE 'SYNTH_ARGS .*|POSTSYNTH_FULLCORE_WNS_NS [-0-9.]+|POSTROUTE_FULLCORE_WNS_NS [-0-9.]+|ACHIEVED_FMAX_MHZ [0-9.]+' "$RUNROOT/$TAG.out" | tr '\n' ' ')"
done
