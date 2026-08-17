#!/usr/bin/env bash
# Run every host-side debug-contract check. Exit 0 only if all pass.
# The sibling cross-conformance check exits 77 when macqd700-soc is absent;
# that is reported as a SKIP and does not mask a real failure.
set -uo pipefail
cd "$(dirname "$0")/../.."

rc=0
for t in tools/debug/test_debug_regmap.py \
         tools/debug/test_dbg_protocol.py; do
  echo "=== $t ==="
  python3 "$t" || rc=1
done

echo "=== tools/debug/gen_debug_regmap.py --check ==="
python3 tools/debug/gen_debug_regmap.py --check || rc=1

echo "=== tools/debug/test_sibling_conformance.py ==="
python3 tools/debug/test_sibling_conformance.py
case $? in
  0)  ;;
  77) echo "SKIP: macqd700-soc checkout absent (set MACQD700_SOC)" ;;
  *)  rc=1 ;;
esac

exit $rc
