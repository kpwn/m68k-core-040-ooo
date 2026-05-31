#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -n "${SPEC:-}" ]]; then
  echo "pm-gate runs broad acceptance only; unset SPEC and use focused targets separately." >&2
  exit 2
fi

echo "[pm-gate] fast tests"
make test-fast

echo "[pm-gate] serialized Verilator tests"
make test-verilator
