# Throughput-v2 congestion cleanup

Baseline: SoC `9048d47e`, CPU `ca3f31da`, 200 MHz, Ethernet and detailed
counters enabled; IPC ILA and SCSI trace disabled. The user reports 100k
Dhrystones/s (51k at 100 MHz). The loaded test image is not timing-closed:
WNS -0.249 ns, 2766 setup-failing endpoints, WHS +0.007 ns, no hold/pulse
failures. Preserve its IPC mechanisms and the running board.

## Evidence and attribution

Read-only reports from the exact final routed checkpoint are in
`/tmp/ipc-v2-congestion-census/`. The full endpoint census agrees with the
timing summary's 2766 failures; do not use the report's per-clock sample as
a distribution of congestion causes. Routed utilization is 154736 total
LUTs (140005 logic, 14027 LUTRAM, 704 SRL), 93804 FFs, 168 RAMB36,
39 RAMB18, 64 URAM, 40 DSPs.

`net_footprint.tsv` covers the 40 nets in `fanout_route.rpt`, not every net
or every block. PIP/node counts are resource-footprint proxies, not physical
wire length or a causal attribution of congestion. Global-buffer nets are
flagged separately. Counts must not be summed across nets as unique tiles.

| Routed net | Loads | PIPs |
|---|---:|---:|
| L2 MSHR primary quadrant bit 1 | 1416 | 4567 |
| Debug CSR read-address bit 3 | 1196 | 4410 |
| L2 MSHR primary quadrant bit 0 | 1416 | 4356 |
| Debug CSR read-address bit 2 | 1195 | 3673 |
| Integer PRF write-1 address bit 1 | 970 | 1175 |

The LUT-driven `u_pram_sd/cpu_rst_bufg_place` is larger still (6065 loads,
10749 PIPs). Its name does not make it a dedicated global-buffer net;
the reported driver type is LUT6. Any reset rewrite needs a separate reset
ownership/safety argument, not a blanket false path or removal of reset.

## Candidates

1. **IQ dependency payload:** stop clearing triangular trigger masks on a
   flush. Occupancy, producer scoreboards and same-cycle issue gates still
   clear/suppress exactly as before. Both trigger consumers qualify with
   occupancy; insertion/compaction replace the whole mask alongside slot
   ownership. No stage, entry, or cycle is added. Keep this isolated from
   larger changes to measure its actual physical effect.
2. **L2 fixed-quadrant merge:** remove the old-data quadrant select followed
   by a dynamic writeback into the same quadrant. Merge at each fixed
   destination slice instead. No state-machine, byte-enable, ordering,
   reset, or response-latency change. Separate SoC worktree/branch:
   `macqd700-soc-worktrees/ipc-v2-l2-cleanup`, `perf/l2-quadrant-cleanup`.
3. **Debug readback:** investigate registered narrow/one-hot decode rather
   than broadcasting binary read-address bits through wide read muxes.
   Preserve the address map, snapshot point, reset domains, AXI backpressure,
   and existing live/history/PRF paths. Not implemented yet.
4. **PRF/ROB payload distribution:** inspect wide write-address/LVT networks
   after the lower-risk changes; do not remove ports or throughput blindly.

## Verification state

- IQ directed suite: 10/10 pass, including nonzero dependencies, concurrent
  push/flush, and more than a queue's worth of subsequent slot reuse.
- Initial suite run exposed stale six-bit ROB IDs in two existing tests
  and the new test; corrected test stimuli for the current five-bit ROB,
  without changing RTL widths or interfaces.
- CPU fast gate: 388 passed, 2 ignored, `/tmp/ipc-cleanup-fast.log`.
- Serialized checks: `/tmp/run-ipc-cleanup-checks.sh`, log
  `/tmp/ipc-cleanup-checks.log`. Runs identical L2 directed/stress tests
  against original and candidate RTL, then board-copy IPC with both v2
  store optimizations. Do not overlap another simulator job.
- Matched CPU board-copy reference: `/tmp/early-store-data-copy.log`.
  Matched candidate: `/tmp/ipc-cleanup-iq-copy-l2.log` (`IPC_MEM=l2:5:70`).
  The initial `/tmp/ipc-cleanup-iq-copy.log` run passed all 16 seed/kernel/profile
  combinations but used ideal zero-latency memory: do NOT compare that run
  against the L2-faithful reference. Compare retired instructions,
  cycles, branches/misses and byte verification for every seed/kernel.
- Physical benefit and 200 MHz closure are unproven until measured. No
  cleanup candidate has been loaded on the board.

L2 original and candidate both pass 68 directed checks and five stress seeds
of 100000 operations each. All reported cyc/op rows and whole-run pipeline
cycle counts match exactly, including stalls and re-read counts. Logs:
`/tmp/ipc-cleanup-l2-{before,after}.log`. This is L2 throughput/correctness
evidence, not a new board IPC measurement.

Matched CPU L2-faithful copy regression is complete: all 16 reported rows
(baseline/combined profiles, four kernels, two seeds) match the pre-cleanup
reference exactly, including retired counts, cycles, branch misses and store
publication counts. The 32-byte combined loop remains 360 cycles; the
128-byte combined loop remains 1945 cycles for either seed. This establishes
no IPC loss on those windows, not on every workload.

Isolated MSHR synthesis (same part/options) reduces LUTs from 4554 to 4144
(-410, -9.0%); logic LUTs 4068 to 3658, LUTRAM unchanged at 486, FFs
2034 to 2032. Reports: `/tmp/ipc-cleanup-l2-synth/`. These are OOC synthesized
counts, not the routed whole-SoC module counts. One caution: maximum state-bit
fanout moves from 1304 to 1825, while the other large state bit falls from
1089 to 583. Do not claim a blanket fanout reduction or timing success from
the area delta: the next required experiment is integrated placement/routing.
