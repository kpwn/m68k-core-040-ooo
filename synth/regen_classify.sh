#!/usr/bin/env bash
# Netlist-pinning workflow (congestion dossier D1): SpinalHDL regen is bistable
# (exactly two _zz_ orderings; same .v -> bit-identical post-route, proven x4).
# This script regenerates N times, md5-classifies, and archives one .v per class
# under synth/pinned/<md5-12>.v so gates can consume a PINNED netlist and A/B
# comparisons are never confounded by the ordering flip.
#
# Usage: synth/regen_classify.sh [N_REGENS]   (default 6)
# Requires: the sbt JVM slot (do NOT run while a forked test JVM or vivado runs).
set -u
cd "$(dirname "$0")/.."
N="${1:-6}"
mkdir -p synth/pinned
echo "regen-classify: $N regens"
declare -A SEEN
for i in $(seq 1 "$N"); do
  JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" >/dev/null 2>&1
  md5=$(md5sum generated/M68kFullCoreSynth.v | cut -c1-12)
  if [[ -z "${SEEN[$md5]:-}" ]]; then
    SEEN[$md5]=1
    cp generated/M68kFullCoreSynth.v "synth/pinned/${md5}.v"
    # the gshare PHT $readmem sidecar must travel with the netlist
    cp generated/M68kFullCoreSynth.v_toplevel_GsharePlugin_logic_pht.bin \
       "synth/pinned/${md5}.v_toplevel_GsharePlugin_logic_pht.bin" 2>/dev/null
    echo "regen $i: md5=$md5  NEW class -> pinned"
  else
    echo "regen $i: md5=$md5  (seen)"
  fi
done
echo "classes found: ${!SEEN[@]}"
echo "To gate a pinned class: cp synth/pinned/<md5>.v generated/M68kFullCoreSynth.v"
echo "  (+ the pht .bin sidecar), then vivado -mode batch -source synth/impl_FullCore.tcl"
