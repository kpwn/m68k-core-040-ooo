#!/usr/bin/env bash
# Vendor m68k-ooo's directed asm tests into this repo's test resources.
#
# Copies .s files that need no interrupt-timing sweep (no .args file, or a
# .args file containing ONLY +timeout=<N>) into DST_DIR, writing a bare
# <name>.timeout sidecar (just the cycle count, no plusarg syntax) for the
# latter case. Tests requiring +ipl= or any other flag are skipped.
#
# usage:
#   tools/fuzz/vendor-ported-tests.sh <src-asm-dir> <dst-dir> --list <namefile>
#   tools/fuzz/vendor-ported-tests.sh <src-asm-dir> <dst-dir> --all
set -euo pipefail
SRC=$1
DST=$2
MODE=$3
mkdir -p "$DST"

qualifies() {
  local name=$1
  local args="$SRC/$name.args"
  if [ ! -f "$args" ]; then return 0; fi
  if grep -q '+ipl=' "$args"; then return 1; fi
  # any line that ISN'T a comment, blank, or a bare +timeout=<N> disqualifies it
  if grep -vE '^\s*(#|\+timeout=[0-9]+\s*$|\s*$)' "$args" | grep -q .; then return 1; fi
  return 0
}

vendor_one() {
  local name=$1
  if [ ! -f "$SRC/$name.s" ]; then
    echo "  [MISS] $name (no .s file in $SRC)"
    return
  fi
  if ! qualifies "$name"; then
    echo "  [SKIP] $name (needs ipl-sweep or other unsupported flags)"
    return
  fi
  cp "$SRC/$name.s" "$DST/$name.s"
  local args="$SRC/$name.args"
  if [ -f "$args" ]; then
    grep -o '+timeout=[0-9]*' "$args" | cut -d= -f2 > "$DST/$name.timeout"
  fi
  echo "  [OK]   $name"
}

case "$MODE" in
  --list)
    namefile=$4
    while IFS= read -r name; do
      [ -z "$name" ] && continue
      vendor_one "$name"
    done < "$namefile"
    ;;
  --all)
    for s in "$SRC"/*.s; do
      name=$(basename "$s" .s)
      if [ -f "$DST/$name.s" ]; then continue; fi   # already vendored (e.g. by the pilot)
      vendor_one "$name"
    done
    ;;
  *)
    echo "usage: $0 <src-asm-dir> <dst-dir> --list <namefile> | --all" 1>&2
    exit 2
    ;;
esac
