#!/usr/bin/env bash
# Same source/configuration and implementation recipe, differing only in the
# explicit LSU latency elaboration options. Retain all worktrees and logs.
set -euo pipefail
repo_root="$(git rev-parse --show-toplevel)"
ref="$(git rev-parse "${1:-HEAD}^{commit}")"
gate_root="$(mktemp -d /tmp/ls-latency-gate.XXXXXX)"
echo "LS_LATENCY_GATE_ROOT=$gate_root REF=$ref"
export JAVA_OPTS="${JAVA_OPTS:--Xmx6G -Xms512M}"
export POSTROUTE_ROUNDS="${POSTROUTE_ROUNDS:-3}"
read -r -a modes <<< "${LS_LATENCY_MODES:-baseline fallthrough}"
for mode in "${modes[@]}"; do
  case "$mode" in baseline|fallthrough|earlywake|combined) ;; *) echo "invalid mode: $mode" >&2; exit 2 ;; esac
  arm="$gate_root/$mode"
  git -C "$repo_root" worktree add --detach "$arm" "$ref"
  gen='runMain m68k040.top.GenFullCoreSynthVerilog'
  if [[ "$mode" == fallthrough || "$mode" == combined ]]; then gen+=' --aligned-load-fall-through'; fi
  if [[ "$mode" == earlywake || "$mode" == combined ]]; then gen+=' --early-ls-int-wakeup'; fi
  (
    cd "$arm"
    "${SBT:-/home/qwertyoruiop/sbt/bin/sbt}" "$gen" > "$gate_root/$mode-elaboration.log" 2>&1
    sha256sum generated/M68kFullCoreSynth.v > "$gate_root/$mode-netlist.sha256"
    # Share the existing synthesis mutex; no board/cable access is involved.
    flock /var/tmp/m68k-ooo-vivado.lock \
      vivado -mode batch -source synth/impl_FullCore.tcl > "$gate_root/$mode-implementation.log" 2>&1
  )
  # Systemd's minimal PATH may not contain Codex's bundled ripgrep.
  if command -v rg >/dev/null 2>&1; then
    rg '^(SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_RESULT)' \
      "$gate_root/$mode-implementation.log" > "$gate_root/$mode-summary.txt"
  else
    grep -E '^(SIGNOFF_200MHZ_|POSTROUTE_FULLCORE_RESULT)' \
      "$gate_root/$mode-implementation.log" > "$gate_root/$mode-summary.txt"
  fi
  echo "LS_LATENCY_ARM_DONE=$mode"
done
echo "LS_LATENCY_GATE_DONE=$gate_root"
