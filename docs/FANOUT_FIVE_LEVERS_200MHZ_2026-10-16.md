# Five fanout levers: matched 200 MHz measurement (2026-10-16)

CPU commit `6cfecab0` adds five independent, default-off RTL flags:
`CPU_LS_PARK_NOLINE`, `CPU_TLB_DRAIN_NARROW`, `CPU_DC_FREE_CAPTURE`,
`CPU_DC_PROBE_MH_LAG`, and `CPU_DBG_APPLY_NOSNAP`. The comparison below uses
that **same commit** with all five flags off or all five on. The shipping
defaults remain off because the enabled board build still misses 200 MHz.

## Functional and IPC gates

* `make SBT=~/sbt/bin/sbt test-fast`: 430 passed, 0 failed with the flags off;
  430 passed, 0 failed with all five on. Each run had one canceled and two
  ignored tests.
* Matched IPC: all 34 kernels had identical cycles, 551,700 total in each
  configuration. Both runs used seed 1, zero init, shipping profile and
  `l2:5:60:4096` memory.
* Ported corpus and copyback/MMU sweeps: no new failure **names** versus the
  previous `fanout2` gate. Both have `exc_partial_macro_move_mem_mem` in
  `ported` and `ported-sweep-copyback`. The current gate also has two expected
  cancels because `CPU_FP_UNSUPP_TRAP` is off by default.
* Lockstep: the previous 703-name shard gate had 702 passes, one ignored and
  no failures on both heads. An additional 49 registered test names missing
  from that shard list passed on the current head, covering all 752 registered
  names (751 passed, one ignored, no failures).

## Physical measurements

The board comparison used SoC commit `8eb5adf`, Vivado 2025.2,
`make impl-fourcycle`, `TARGET_FREQ_MHZ=200`, real DDR4 MIG, Ethernet, the
throughput-v2 CPU profile, the split place/route flow, no incremental
checkpoint and `AggressiveExplore` routing. Both provenance files record CPU
commit `6cfecab0`, `cpu040_dirty=no`, and `core_clk_hz=200000000`.

| Full SoC metric | Five flags off | Five flags on | On minus off |
| --- | ---: | ---: | ---: |
| Placed setup WNS | -1.563 ns | -0.832 ns | +0.731 ns |
| Routed setup WNS | -0.495 ns | -0.157 ns | +0.338 ns |
| Routed setup TNS | -1302.918 ns | -29.441 ns | +1273.477 ns |
| Failing setup endpoints | 6407 | 534 | -5873 |
| Routed hold WHS | 0.000 ns | +0.011 ns | +0.011 ns |
| Routed LUTs | 144909 | 144935 | +26 |
| Routed flip flops | 97142 | 95596 | -1546 |

The enabled route cuts failing setup endpoints by 91.7% and flip flops by
1.6%; routed LUT use is effectively unchanged. Its worst setup path is
5.157 ns, corresponding to about 193.9 MHz. **The 200 MHz constraint is not
met**, so the generated bitstream is an experiment, not a timing-clean image.
This is one paired physical run; placement variance remains a limitation.

The separate core-only OOC comparison used the shipping/memory IPC profile
with a four-entry load ring. There, the five flags reduced LUTs from 94558 to
93700 and flip flops from 48835 to 47488; routed WNS improved from -0.385 ns
to -0.348 ns. The board `impl-fourcycle` profile uses an eight-entry load ring,
so its resource totals are the relevant numbers for the board result above.

## Reproducibility artifacts

* Board output: `/var/tmp/codex-fanout-soc-200/{off,on}/`, including
  `cpu040.provenance`, `timing_summary.rpt`, `reports/utilization_route.rpt`,
  routed checkpoints and bitstreams. Launch logs:
  `/var/tmp/codex-fanout-soc-200-{off,on}.log`.
* Staged default-off fast gate:
  `/var/tmp/codex-fanout-current-staged-default-test-fast.log`.
  Enabled fast gate: `/var/tmp/codex-fanout-current-on-test-fast.log`.
* Matched IPC logs: `/var/tmp/codex-fanout-current-{off,on}-ipc.log`.
* Corpus and lockstep named summaries: the `scratchpad/gate/arms/`
  `corpus_codex_fanout_current`, `ls_codex_fanout_current` and
  `ls_codex_fanout_missing` directories in the experiment workspace.
