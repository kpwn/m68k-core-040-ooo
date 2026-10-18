# Five fanout levers: matched 200 MHz measurement (2026-10-16)

CPU commit `6cfecab0` adds five independent, default-off RTL flags:
`CPU_LS_PARK_NOLINE`, `CPU_TLB_DRAIN_NARROW`, `CPU_DC_FREE_CAPTURE`,
`CPU_DC_PROBE_MH_LAG`, and `CPU_DBG_APPLY_NOSNAP`. The comparison below uses
that **same commit** with all five flags off or all five on. At that revision,
the shipping defaults remained off because the enabled board build still missed
200 MHz. A later timing-clean result on the recovered board profile is below.

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

## Recovered board profile: timing-clean area reduction (2026-10-18)

CPU `96f38ad3` enables all five flags by default on top of `4dd1124d`. Both
board arms use SoC `8284a403`, Vivado 2025.2, the same 200 MHz MMCM,
`throughput-v2`, reduced debug, nonblocking L1D, the CPU AXI hot door, real
DDR4 MIG, and no incremental checkpoint. The SD controller in this SoC uses
the recovered 25 MHz high-speed divider. The only CPU source difference is the
five defaults. The candidate's build selection records clean CPU and SoC
source trees.

| Full SoC metric | Recovered baseline | Five flags on | On minus baseline |
| --- | ---: | ---: | ---: |
| Routed setup WNS | +0.033 ns | +0.031 ns | -0.002 ns |
| Routed hold WHS | +0.010 ns | +0.010 ns | 0.000 ns |
| Pulse-width WPWS | +0.032 ns | +0.040 ns | +0.008 ns |
| Pulse-width failures | 0 | 0 | 0 |
| Routed LUTs | 143377 | 141936 | -1441 |
| Routed flip flops | 96874 | 95604 | -1270 |
| BRAM36 / BRAM18 / URAM | 170 / 40 / 64 | 170 / 40 / 64 | unchanged |

The candidate wrote a routed checkpoint and bitstream with zero DRC errors.
Its 200 MHz setup, hold, and pulse-width constraints all pass. The full build
crashed at Vivado's leaf-clock optimization; `resume_from_place.tcl` completed
the route from the saved place checkpoint with `AggressiveExplore`, as it did
for the baseline.

`make SBT=~/sbt/bin/sbt test-fast` passed: 430 succeeded, zero failed, one
canceled and two ignored. With the same candidate source and five environment
overrides forced OFF or left ON, all 34 IPC microbenchmark kernels had
identical window cycles (271573 retired macros in 548523 cycles, 0.495 IPC).

The local board image booted Finder and completed two Speedometer Benchmark
Mix passes at 199.63–199.64 MHz. A follow-up reload-to-reload four-pass pair
measured baseline 0.4968 IPC and 10.605 CPU rating versus candidate 0.4962
IPC and 10.611 CPU rating at 199.62 MHz. The 0.14% IPC difference is smaller
than the pass-to-pass range, while the CPU rating changes by +0.06%; these
measurements do not establish an IPC gain or regression on Speedometer. The
34-kernel simulation gives stronger evidence that the five flags do not change
that suite's cycles. Other workloads remain unmeasured on the board.

Artifacts: `/var/tmp/codex-sdperf-recovered-200/vivado/` and
`/var/tmp/codex-ipc-area-200/vivado/` contain the matching build records,
timing/utilization reports, routed checkpoints, and bitstreams. The candidate
route log is `/var/tmp/codex-ipc-area-200-resume.log`; its fast gate is
`/var/tmp/codex-ipc-area-test-fast.log`, IPC A/B logs are
`/var/tmp/codex-ipc-area-{off,on}-ipc.log`, and the four-pass board results are
`/var/tmp/codex-ipc-{base,area}-4run/result.json`.

The earlier opt-in four-cycle L1/L2 profile was also regenerated against these
recovered sources. It increased synthesis use by 2188 LUTs and 1260 flip flops,
and placed WNS was -1.336 ns; physical optimization improved that estimate to
about -1.24 ns. Its place checkpoint is preserved at
`/var/tmp/codex-ipc-fast-200/vivado/checkpoints/place.dcp`. The implementation
was stopped before routing because that profile remained far outside the 200
MHz timing margin. It was not loaded on the board.

## IPC follow-up (2026-10-19)

The first two-pass board pair also favored the baseline in IPC: 0.49847 versus
0.49724 (results in `/var/tmp/codex-ipc-{current,area}-board-ab/result.json`).
Combined with the later four-pass pair above, the six-run means are
0.49739 for the baseline and 0.49652 for the area image, a 0.17% lower mean
for the area image. The individual ranges overlap, and the Speedometer CPU
ratings remain effectively equal, so the board data do not isolate a causal
RTL regression. Nonetheless, the area image does not meet a strict no-IPC-loss
handoff criterion. The five flags therefore return to default OFF; their
implementations remain available for individually measured experiments. This
restores the executable CPU source to the previously measured `4dd1124d`
baseline. The board was occupied by SD provisioning during this source change,
so the restored defaults were not reloaded in this follow-up.
