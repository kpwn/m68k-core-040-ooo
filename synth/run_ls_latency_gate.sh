#!/usr/bin/env bash
# Same source/configuration and implementation recipe, differing only in the
# explicit LSU fall-through elaboration option. Retain both worktrees and logs.
set -euo pipefail
repo_root="$(git rev-parse --show-toplevel)"
ref="$(git rev-parse "${1:-HEAD}^{commit}")"
gate_root="$(mktemp -d /tmp/ls-latency-gate.XXXXXX)"
echo "LS_LATENCY_GATE_ROOT=$gate_root REF=$ref"
export JAVA_OPTS="${JAVA_OPTS:--Xmx6G -Xms512M}"
export POSTROUTE_ROUNDS="${POSTROUTE_ROUNDS:-3}"
for mode in baseline fallthrough; do
  arm="$gate_root/$mode"
  git -C "$repo_root" worktree add --detach "$arm" "$ref"
  gen='runMain m68k040.top.GenFullCoreSynthVerilog'
  if [[ "$mode" == fallthrough ]]; then gen+=' --aligned-load-fall-through'; fi
  (
    cd "$arm"
    "${SBT:-/home/qwertyoruiop/sbt/bin/sbt}" "$gen" > "$gate_root/$mode-elaboration.log" 2>&1
    sha256sum generated/M68kFullCoreSynth.v > "$gate_root/$mode-netlist.sha256"
    # Share the existing synthesis mutex; no board/cable access is involved.
    flock /var/tmp/m68k-ooo-vivado.lock \
      vivado -mode batch -source synth/impl_FullCore.tcl > "$gate_root/$mode-implementation.log" 2>&1
  )
  rg 'SIGNOFF_200MHZ_|ACHIEVED_FMAX_MHZ|POSTROUTE_FULLCORE_RESULT' \
    "$gate_root/$mode-implementation.log" > "$gate_root/$mode-summary.txt"
  echo "LS_LATENCY_ARM_DONE=$mode"
done
echo "LS_LATENCY_GATE_DONE=$gate_root"
