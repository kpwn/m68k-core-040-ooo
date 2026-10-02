#!/usr/bin/env bash
# Matched core-model preflight for adding NB4 and the hot door to the LS-OoO reference.
# Run only in the PM's serialized Verilator window. This does not build or program an FPGA.
set -euo pipefail

mode=${1:---plan}
case "$mode" in --plan|--run) ;; *) echo 'usage: nb4_ls_ooo_preflight.sh [--plan|--run]' >&2; exit 2 ;; esac

repo=$(git rev-parse --show-toplevel)
source_rev=33ae19f5c5b7a3d3a45cb250584129f88694cc88
changed=$(git -C "$repo" diff --name-only "$source_rev" HEAD -- src build.sbt project Makefile |
  sed '/^src\/test\/scala\/m68k040\/bench\/LsOooParityManifest.scala$/d')
test -z "$changed" || {
  echo "RTL/test/build source differs from $source_rev" >&2
  exit 1
}
test -z "$(git -C "$repo" status --porcelain --untracked-files=no)" || {
  echo 'CPU source has tracked edits; refusing a mixed-source comparison' >&2
  exit 1
}

out=${NB4_LS_PREFLIGHT_OUT:-/tmp/codex-nb4-ls-ooo-preflight-33ae}
test ! -e "$out" || { echo "Output already exists: $out" >&2; exit 1; }
if [[ "$mode" == --plan ]]; then
  printf 'CPU source %s (checkout %s)\nComparison: LS-OoO only vs LS-OoO+D4/NB4/hot-door\nOutput: %s\n' \
    "$source_rev" "$(git -C "$repo" rev-parse HEAD)" "$out"
  printf 'PLAN ONLY: reserve the PM-serialized Verilator slot, then invoke --run.\n'
  exit 0
fi
mkdir -p "$out"
printf '%s\n' "$source_rev" > "$out/cpu-source.sha"
git -C "$repo" rev-parse HEAD > "$out/checkout.sha"
sha256sum "$repo/tools/nb4_ls_ooo_preflight.sh" > "$out/runner.sha256"

# Hold one LS-OoO core-model profile fixed. Only D4/NB4/hot-door change.
common=(
  HOME=/home/qwertyoruiop USER=qwertyoruiop LOGNAME=qwertyoruiop
  LANG=C.UTF-8 PATH=/home/qwertyoruiop/sbt/bin:/usr/local/bin:/usr/bin:/bin
  SBT_OPTS='-Xmx6g -Xss16m'
  CPU_IPC_PROFILE=throughput-v2 CPU_DEBUG_PROFILE=full
  IQ_LOAD_BYPASS=1 IPC_MEM=l2:5:60:4096
  DSIDE_KERNELS=memcpy-16k,stream-16k,chase-128,chase-chains-4-1024-skew0
  DSIDE_CHAIN_COUNTS=4 DSIDE_SEEDS=1,17
  CPU_LS_LOAD_RING_DEPTH=4 CPU_DCACHE_MSHRS=4 CPU_DCACHE_SECTORED=0
  CPU_LS_P1_EARLY_LOAD=0 CPU_LS_P3_FAST_LOAD=0 CPU_LS_FOUR_CYCLE_L1=0
  CPU_DCACHE_NB_EARLY_RESPONSE=0 CPU_DCACHE_NB_EAGER_AR=0
  CPU_DCACHE_NB_PRESELECT_AR=0 CPU_DCACHE_NB_DYNAMIC_RELEASE=0
  CPU_DCACHE_NB_PROBE_MISS_STAGE=0 CPU_AXI_DH_AR_FALL_THROUGH=0
)

for arm in ls_only combined; do
  if [[ "$arm" == ls_only ]]; then value=0; else value=1; fi
  printf 'arm=%s D4/NB4/DH=%s\n' "$arm" "$value" | tee "$out/$arm.config"
  (
    cd "$repo"
    env -i "${common[@]}" \
      CPU_INHIBITED_FULL_BARRIER="$value" \
      CPU_DCACHE_NONBLOCKING="$value" CPU_AXI_DH="$value" \
      /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.DsideBandwidthSpec'
  ) > "$out/$arm.log" 2>&1
  grep '^DSIDE_BW ' "$out/$arm.log" > "$out/$arm.bw"
  test "$(wc -l < "$out/$arm.bw")" -eq 8
  grep -q 'All tests passed' "$out/$arm.log"
done

python3 - "$out" <<'PY'
import json, pathlib, re, sys
out = pathlib.Path(sys.argv[1])
def read(arm):
    rows = {}
    for line in (out / (arm + '.bw')).read_text().splitlines():
        fields = dict(re.findall(r'([A-Za-z/]+)=([^ ]+)', line))
        key = (fields['kernel'], int(fields['seed']))
        rows[key] = {k: fields[k] for k in ('bytes', 'cycles', 'retired', 'IPC', 'B/cyc')}
    return rows
a, b = read('ls_only'), read('combined')
assert len(a) == len(b) == 8 and a.keys() == b.keys()
result = []
for key in sorted(a):
    assert a[key]['bytes'] == b[key]['bytes']
    assert a[key]['retired'] == b[key]['retired'], key
    ac, bc = int(a[key]['cycles']), int(b[key]['cycles'])
    result.append(dict(kernel=key[0], seed=key[1], ls_only_cycles=ac,
                       combined_cycles=bc, speedup=round(ac / bc, 5),
                       retired=int(a[key]['retired'])))
(out / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
for row in result:
    print(f"{row['kernel']} seed={row['seed']} cycles {row['ls_only_cycles']} -> "
          f"{row['combined_cycles']} speedup={row['speedup']:.5f}")
PY
