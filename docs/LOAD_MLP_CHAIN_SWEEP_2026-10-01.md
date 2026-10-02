# Independent-load chain sweep, 2026-10-01

This is **core-only simulation evidence**, not routed timing or a proposed
shipping default. The experiment uses the NB4 hot-door D-cache with the
early-response and eager-AR options, LS out-of-order load issue, fused
longword MOVE loads, speculative load wakeup, P3 fast admission, and registered
early-probe line forwarding enabled. Direct legacy refill is disabled. Memory
is `l2:5:60:4096` (the model's configured hot-hit latency is 5; accepted
hot AR to R is 6 cycles in an isolated dependent chase). Seed is 1. The
preselected-AR change was **not** in this experiment's source base; results
must not be mixed with a preselect-ON comparison without rerunning both arms.

`kChaseChains` creates 1, 2, 4, or 8 independent pointer chains in a fixed
1024-record, 16-byte-per-record working set. All use the same indexed
`MOVE.L (A0,Dn.L),Dn` form. Every arm executes 2056 measured pointer loads;
the first traversal is excluded, and every retired pointer result is checked
against its seeded permutation. The loop uses A1, never the stack pointer.
The number of loop/control instructions *per load* changes with chain count,
so the sweep shows an end-to-end workload effect rather than a pure MLP law.
All occupancy/issue figures below use the exact commit window; accepted
AR-to-R durations include only requests whose AR and R are both in it.

| Chains | Ring | Skew | Window cycles | Read B/cycle | Retired instructions | NB `failFull` attempt cycles | Ring full, no pop cycles | Hot AR-to-R median / mean |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 8 | 0 | 36,984 | 0.2224 | 8,224 | 0 | — | 6 / 6.000 |
| 2 | 8 | 0 | 18,982 | 0.4333 | 5,140 | 0 | — | 6 / not measured |
| 4 | 8 | 0 | 11,065 | 0.7432 | 3,598 | 0 | 0 | 7 / 8.480 |
| 8 | 8 | 0 | 12,575 | 0.6540 | 2,827 | 1,487 | 3,161 | 7 / 8.860 |
| 8 | 16 | 0 | 12,573 | 0.6541 | 2,827 | 1,577 | 0 | 7 / 9.015 |
| 4 | 8 | 1 | 11,104 | 0.7406 | 3,598 | 0 | 0 | 7 / 8.712 |
| 8 | 8 | 1 | 12,660 | 0.6496 | 2,827 | 1,517 | 3,427 | 7 / 9.117 |

The 16-entry ring is a matched control for eight chains: same source,
memory model, seed, addresses, instruction shape, and NB4 configuration;
only `CPU_LS_LOAD_RING_DEPTH` changes. It removes measured ring-full state
without improving throughput. This rejects a ring-depth increase **alone**
as an answer for this arm. The raw 1,558 generated-register-bit delta in
`docs/PERFORMANCE_CANDIDATE_ACCEPTANCE.md` belongs to ring **4→8**, not
8→16; no 8→16 area delta was measured here. `setSkew=1` offsets each chain
base by 16 bytes while keeping record count and instruction shape fixed;
it does not remove the eight-chain regression. It changes exact addresses
and slightly expands the address span, so it is a set-placement control,
not a bit-identical-memory control.

`failFull` counts cycles where a staged allocation attempt sees no FREE MSHR,
not distinct missed requests; a retried request may increment it again.
Ring-full cycles describe state, not lost cycles. The timed eight-chain
skew-0 arm averages 7.006 of 8 ring slots occupied, 1.445 hot reads between
accepted AR and R, and 0.191 ready IQ loads. Four chains average 3.067 ring
slots, 1.574 hot reads, and 1.440 ready IQ loads. The hot-read mean satisfies
Little's-law scale for the measured request rate (roughly
`0.6540/4 × 8.860 = 1.45`), while ring occupancy includes pipeline and
MSHR-wait time. Neither metric alone justifies a capacity increase.

The exact `failFull` event snapshot on eight-chain skew-0 finds 582 of 1,487
attempt cycles with at least one MSHR in FILLED or LINGER. At those 1,487
events, mean state occupancies are WAIT_R 2.083, FILLED 0.126, and LINGER
0.309; 108 event cycles have only WAIT_R entries. The skew-1 arm is similar:
606 of 1,517 have FILLED or LINGER and mean LINGER occupancy is 0.304.
These numbers motivate a guarded lifetime/credit experiment, but do not show
that shortening LINGER is safe or that it will recover all blocked attempts.
The existing three-count LINGER protects a stale BRAM-read/miss pipeline;
same-set in-flight reads, late waiter capture, fault, and cancellation must
remain correct before any earlier reclaim can be accepted.

The separate LS completion-port candidate is too small for this regression.
In the eight-chain skew-0 window, a younger early writeback is eligible in
254 cycles, fires in 242, and is blocked in 12; only **10** blocked cycles
coincide with popping a head whose writeback already occurred. Skew-1 has
only **7** such cycles. No front completion is held by those head pops in
either arm. Although the source permits a guarded no-double-write proof for
using that idle port, this workload does not justify adding the head-WB mux
to the completion control path as a throughput change.

Reproduction uses `DsideBandwidthSpec` with `DSIDE_CHAIN_COUNTS=1,2,4,8`,
`DSIDE_CHAIN_RECORDS=1024`, `DSIDE_CHAIN_SET_SKEW=0|1`,
`DSIDE_SEEDS=1`, `IPC_SEED=1`, `IPC_MEM=l2:5:60:4096`,
`CPU_DCACHE_NONBLOCKING=1`, `CPU_DCACHE_MSHRS=4`, `CPU_AXI_DH=1`,
`CPU_INHIBITED_FULL_BARRIER=1`, `CPU_LS_LOAD_RING_DEPTH=8|16`,
`IQ_LOAD_BYPASS=1`, `DSIDE_FUSE_LONG_MOVE_LOADS=1`,
`DSIDE_SPEC_WAKE=1`, `CPU_LS_P3_FAST_LOAD=1`,
`CPU_DCACHE_EARLY_PROBE_LINE_FORWARD=1`,
`CPU_DCACHE_NB_EARLY_RESPONSE=1`, `CPU_DCACHE_NB_EAGER_AR=1`, and
`CPU_DCACHE_DIRECT_REFILL=0`. Logs:
`/tmp/agent69-chain-sweep-l2-fixed.log`,
`/tmp/agent69-chain8-ring16-skew0.log`,
`/tmp/agent70-chain-state-ring8-diagnostic.log`, and
`/tmp/agent70-chain-state-ring8-skew1.log`. The test-only harness commit is
`b2fe7397`; its `make test-fast` gate passed 404/404 (2 ignored) in
`/tmp/agent70-chain-diagnostic-testfast.log`.

## Matched integrated AR-preselection control

Integrated source `97b7453a` includes the inactive metadata foundation and the
same chain telemetry. Its clean fast gate passes 404 tests. Both matched arms
below pass all checked pointer results with the same seed, L2 model, 1024
records, skew0, NB4/ring8 and all earlier latency options ON; only
`CPU_DCACHE_NB_PRESELECT_AR` changes. Direct refill remains OFF.

| Chains | Preselect | Measured cycles | B/cycle | IPC | MSHR-full attempt cycles |
| --- | --- | ---: | ---: | ---: | ---: |
| 4 | OFF | 11065 | 0.7432 | 0.3252 | 0 |
| 4 | ON | 10600 | 0.7758 | 0.3394 | 0 |
| 8 | OFF | 12575 | 0.6540 | 0.2248 | 1487 |
| 8 | ON | 12185 | 0.6749 | 0.2320 | 1545 |

Every row transfers 8224 measured bytes; four-chain retires 3598 instructions,
eight-chain 2827. Throughput gains are 4.4% and 3.2% respectively. Eight chains
still underperform four. In the eight-chain ON arm, 817 of 1545 full-MSHR
attempt cycles contain a FILLED/LINGER entry; mean LINGER occupancy on those
attempts is 0.384. The failed-attempt counter rises despite improved throughput:
it counts retries and cannot stand in for elapsed execution time.

Exact environment and source checks are in
`/tmp/codex-agent59-chain-preselect-gates.py`; validated counts and performance
rows are in `/tmp/codex-agent59-chain-preselect-results.json`. Logs are
`/tmp/codex-agent59-chain-preselect-{fast,off,on}.log`. This controls the
preselection interaction before measuring dynamic MSHR release. It is not an
area/timing or board result, and does not enable a shipping default.
