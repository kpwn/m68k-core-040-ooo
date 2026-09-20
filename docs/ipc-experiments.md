# IPC experiment ledger

Record every performance candidate here, including unsuccessful and zero-gain
experiments. Before/after measurements must use the same workload, seeds,
memory configuration and retirement window. An unmeasured candidate is pending,
not an improvement. Simulation IPC is not board IPC or proof of preserved Fmax.

Selection policy: prefer lower complexity and higher useful throughput at routed
200 MHz. A first timing miss does not discard an IPC-positive mechanism: retain
it as a research candidate and log each specific timing repair and its IPC cost.
The [fifteen design alternatives](ipc-design-options.md) are evaluated proposals,
not fifteen implemented or benchmarked improvements.

Execution policy: IPC/correctness work and synthesis are separate queues. Pin
each tested candidate and its measurements, synthesize candidates one at a time,
and continue developing other proposals while routing runs. Do not block the
next IPC experiment on a synthesis result. A failed timing candidate enters a
repair queue; only final acceptance requires the routed timing gate.

For each new experiment record:

- Baseline and candidate revisions, dirty changes if any, and option settings.
- Workload, memory model, seeds, warm-up and exact measured macro/cycle counts.
- Baseline/candidate IPC and percentage change for each workload, including regressions.
- Correctness checks, test results and reproducible commands.
- Matched routed timing and area, or explicitly pending; acceptance/rejection.

## Dhrystone board windows and pipeline profiling — 2026-09-21

User-confirmed Dhrystone, running throughout. Live build ID `462a4dc1`, matching
`ipc-100mhz/build/vivado/fpga_top.ltx`; build metadata specifies 100 MHz, detailed
counters and IPC ILA enabled. This is the earlier diagnostic CPU, not the new
LSU candidates. No CPU reset, halt, reload or memory write was performed.
Snapshots briefly freeze performance counters only and restore their previous
run state. Windows below count actual running counter cycles, not JTAG overhead.

| Window | Cycles | Macros | IPC | Head load wait | Head store wait | IQ-blocked dispatch | ROB-full / ROB-blocked dispatch |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 300,034,022 | 67,307,899 | 0.224334 | 17.776% | 27.878% | 55.496% | 0 / 0 |
| 2 | 299,856,320 | 66,866,247 | 0.222994 | 17.369% | 27.912% | 55.418% | 0 / 0 |
| 3 | 299,915,342 | 71,798,667 | 0.239396 | 16.256% | 29.888% | 58.925% | 0 / 0 |
| 4 | 299,904,352 | 69,047,560 | 0.230232 | 17.091% | 28.683% | 57.151% | 6 / 7 cycles |

These are **full-system windows while Dhrystone runs**, not PC-filtered user-code
measurements. All show zero halted/exception-FSM cycles. Head classification says
which incomplete operation blocks retirement; it does not identify the underlying
reason (cache miss, dependency, precise-store policy, queueing, etc.). The almost
absent ROB-capacity stalls lower the immediate priority of enlarging/recycling
ROB storage; they do not prove bulk retirement has no benefit.

Window 4 additionally recorded 14,570,022 BTB-eligible retired branches,
2,730,181 non-return mispredict redirects and 543,700 return redirects. The
non-return miss/BTB-branch ratio is about 18.74%; **do not divide total misses
including returns by this branch counter**. Return retirement count is absent,
so whole-branch accuracy is not established by these board counters. There were
86,498 D-cache load misses, 16,253 I-cache demand misses, 7,766 DTLB walks and
4,280 ITLB walks. D-cache busy was 1,616,091 cycles; walker busy 244,522.
Cache/walker busy predicates are not the same as LSU/head-wait predicates.

Live configuration: `TC=0000c000`, `CACR=80008000`,
`DTT0/ITT0=f900c060`, `DTT1/ITT1=807fc040`, `SRP=03fffa00`, `URP=00000000`.
Translation is enabled; don't treat host physical reads as arbitrary CPU virtual
reads or infer every data page's cache policy from CACR alone.

Eight immediate ILA captures each contain 1,024 consecutive 100 MHz cycles,
10.24 us each, with gaps between captures. All passed one-hot event-partition
and same-edge macro-probe/event consistency checks. Across their 8,192 sampled
cycles: 1,690 macros (IPC 0.206299), 52.869% IQ-blocked dispatch, 19.995% head-load
wait and 25.085% head-store wait; no ROB-capacity block. Individual IPC spans
0.096680–0.286133. Some captures include ROM/system PCs, consistent with the
full-system workload and variation; this sample is not a replacement for the
three-second counter windows. Recurring no-macro gaps include 68 cycles between
retired PCs `03bd6910` and `03bd6912`, and 22 cycles between `03be19cc` and
`03be19c2`. Bracketing PCs are **not** proof of which instruction caused a gap.

Raw board evidence is in the persistent `/tmp/jtag_out`, window markers
`DHRYSTONE_WINDOW_1..3` and `DHRYSTONE_FULL_WINDOW_4`, and
`/tmp/dhrystone_20260921_0030_0.csv` through `_7.csv`. The read-only analyzer
`tools/analyze_ipc_ila.py` validates the schema and reports each capture separately.
Its four unit tests cover counts, boundary gaps, invalid probes/partitions and
truncated captures. No raw board logs are added to the repository.

### Matched simulation profiles

Baseline `4cc38032` plus test-only instrumentation; all optional LSU candidates
off. Same `l2:5:70` model, seeds 1/17. Discard setup and 16 loop iterations
(deep-backlog: four; independent ALU: first 32 macros). Twenty runs compare
instrumentation off/on: **all ten pairs have exactly identical macros/cycles**.
Register results are checked for the loop, alternating, backlog and call kernels.

| Kernel | Seed | Warm macros / cycles | IPC | Retired branches / misses | Accuracy | Head-incomplete cycles | Completed younger backlog cycles | Dual retire with >2 complete prefix | Structural branch-pair opportunities |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| hot loop | 1,17 | 336 / 252 | 1.333333 | 84 / 1 | 98.810% | 0 | 0 | 0 | 0 |
| alternating | 1 | 132 / 135 | 0.977778 | 48 / 2 | 95.833% | 21 | 0 | 11 | 28 |
| alternating | 17 | 132 / 234 | 0.564103 | 48 / 11 | 77.083% | 44 | 3 | 6 | 23 |
| synthetic backlog | 1,17 | 360 / 425 | 0.847059 | 32 / 8 | 75.000% | 173 | 138 | 113 | 17 |
| copyback call/return | 1,17 | 672 / 1342 | 0.500745 | 252 / 1 | 99.603% | 671 | 671 | 168 | 168 |
| independent ALU | 1,17 | 396 / 267 | 1.483146 | 0 / 0 | N/A | 9 | 0 | 0 | 0 |

None exhausted pair allocation capacity. Completed prefixes are not automatically
eligible for bulk commit; structural branch-pair opportunities still need precise
state/trace/debug eligibility. The seed-dependent alternating-branch result needs
longer warm-up and history/recovery investigation, not a blanket claim of 95%
accuracy. The synthetic backlog is intentionally an upper-bound stress pattern.

The first profiler test correctly failed its expected call/return denominator:
the existing `debugBranchRetire` stream emits BTB training, excluding returns.
The corrected profiler joins branch-completion metadata to actual retirement,
including returns, and checks the BTB stream as a subset. Raw ROB pressure is
delayed one sampling edge to match registered commit observations, with assertions
checking alignment. Branches are included by exact macro ordinal so partially
included boundary cycles cannot pollute the denominator. Kind 2 identifies
non-BTB control flow (returns in this corpus), not a new predictor event.

Reproduce:

```sh
IPC_MEM=l2:5:70 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.PipelineProfileSpec'
python3 tools/test_analyze_ipc_ila.py
python3 tools/analyze_ipc_ila.py /tmp/dhrystone_20260921_0030_{0,1,2,3,4,5,6,7}.csv
```

Simulation log: `/tmp/pipeline-profile-all-branches.log`. These are measurement
changes with zero IPC effect, not newly implemented performance improvements.

## Load latency candidates — 2026-09-20

Full-core simulation, `IPC_MEM=l2:5:70`: modeled L2 hit 5 cycles, DDR 70 cycles,
64-byte lines, instruction and data memory. These older tests **include warm-up**;
the window spans first through requested last retired macro. Guard instructions
are excluded. Both flags are off for baseline. IPC counts macros, not micro-ops.

The following seed-1 comparisons are from the matched four-mode run at
`b99636f4`; every cell is retired macros / cycles = IPC.

| Workload | Baseline | Fall-through only | Early wake only | Both |
| --- | --- | --- | --- | --- |
| Pointer chain | 388/2950 = 0.131525 | 388/2693 = 0.144077 | 388/2692 = 0.144131 | 388/2436 = 0.159278 |
| Load stream | 481/753 = 0.638778 | 481/702 = 0.685185 | 481/701 = 0.686163 | 481/650 = 0.740000 |
| Call/return | 805/3292 = 0.244532 | 805/3183 = 0.252906 | 805/3104 = 0.259343 | 805/2982 = 0.269953 |
| Copyback store/load | 290/419 = 0.692124 | 290/419 = 0.692124 | 290/401 = 0.723192 | 290/401 = 0.723192 |

Across seeds 1 and 17, combined gains are: pointer chain 21.10–21.34%,
load stream 15.85–15.92%, same-line disjoint accesses 7.46–8.38%, call/return
8.27–10.40%, copyback store/load 4.24–4.49%, mixed copyback 4.85%, store stream
about 0.16%, delayed-store-data about 0.09%. Dependent/independent ALU, hot loop,
MMU-off store/load and MMU-off mixed show no gain. The divider-bound delayed-store
test is diagnostic, not evidence of a substantial throughput improvement.

Candidate history and disposition:

| Candidate | IPC result | Timing / disposition |
| --- | --- | --- |
| Descriptor fall-through, `ab849ea7` | Load-side gain; no copyback store/load gain alone | Rejected for 200 MHz in original form: matched baseline WNS +0.011 ns, candidate −1.160 ns, 3,275 setup failures. LUTs 93,896 → 92,618. |
| Guaranteed early integer wake, `5c971456`, measured at `b99636f4` | See early-wake column | Matched core OOC 200 MHz pass: WNS +0.030 ns, WHS +0.021 ns, no failing endpoints; default off pending integration. |
| Combined fall-through + early wake | See combined column and full-corpus ranges | Repaired combined implementation in timing queue; default off. |
| Payload-selection timing repair, `b99636f4` | **0% change:** all 104 benchmark rows have identical macro and cycle counts before/after | Timing pending; no Fmax recovery claimed. |
| Macro-counter correction, `b0eeabaa` | Measurement correction, not an RTL speedup; removed extra call/return counts | Use corrected figures only. |
| Warm-window harness extension, based on `b99636f4` | **0% change:** all 104 existing benchmark rows unchanged with default warm-up 0 | Test-only change; new baseline below. |

Timing above is core out-of-context at 5 ns on `xcku5p-ffvb676-2-e`, not complete
SoC signoff. Hold and pulse width passed in the original fall-through run.
The repaired baseline/early-wake/combined matrix is still running.

Update, 2026-09-21: baseline and early-wake modes at pinned `b99636f4` have
finished; combined is still running. Baseline/early-wake routed resource counts
are respectively 93,896/94,283 LUTs (+387, about 0.41%), 38,740/38,727 FFs, and
37/37 BRAM tiles. Baseline setup/hold slack is +0.011/+0.023 ns; early-wake is
+0.030/+0.021 ns. Both have zero setup/hold/pulse-width failing endpoints and
+1.958 ns minimum pulse-width slack. These are matched core-only reports, not
board or SoC signoff. They demonstrate an IPC-positive early-wake candidate can
meet this 200 MHz gate with a small LUT increase; the 19 ps WNS difference is not
claimed as a reproducible Fmax improvement. Reports are `fullcore_route_timing.rpt`
and `fullcore_route_util.rpt` under the matrix's `baseline/synth` and
`earlywake/synth` directories.

Correctness: repaired RTL passed 28 focused LSU cases, 29 selected oracle cases,
104 L2/DDR benchmark configurations, and the fast gate (381 passed, two ignored).
The warm-window extension subsequently passed the 104-configuration regression
and the same fast gate. Detailed analysis: [load latency](ls-hit-latency.md).

Reproduce the comparison:

```sh
IPC_MEM=l2:5:70 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.LsFallThroughIpcSpec'
```

Local evidence (temporary, not release artifacts): `/tmp/ls-payload-select-ipc.log`,
`/tmp/store-load-window-regression.log`, `/tmp/store-load-window-fast.log`.
Original routed reports: `/tmp/ls-latency-gate.gMSKBE/baseline/synth/` and
`/tmp/ls-latency-gate.b4GEMa/fallthrough/synth/`. Repaired matrix:
`/tmp/ls-latency-gate.tyY91B/`.

## Store/load dependency benchmark — baseline, 2026-09-20

Baseline RTL `b99636f4`, both latency options off, with the test-only warm-window
extension. Copyback memory, same L2/DDR model as above. Each chain increments a
register, stores it, then reloads it. Independent chains use addresses 16 bytes
apart. Of 96 loop iterations, discard setup and the first 16 iterations; measure
the final 80. Loop-control instructions count toward IPC. Seeds 1 and 17 produce
identical results. Every producer and reload value is checked, including warm-up.

| Independent chains | Measured macros | Cycles | IPC | Completed pairs/cycle | Cycles/pair |
| --- | --- | --- | --- | --- | --- |
| 1 | 400 | 880 | 0.454545 | 0.090909 | 11.000 |
| 2 | 640 | 880 | 0.727273 | 0.181818 | 5.500 |
| 4 | 1120 | 1104 | 1.014493 | 0.289855 | 3.450 |

These count completed store/load pairs, **not verified forwarding events**: a
load can read cache after its store drains. One chain exposes recurrence latency;
multiple chains expose throughput. Other whole-run diagnostic counters must not
be presented as warm-window counters.

Address-verified PRF forwarding and integrated memory-dependency scheduling are
not implemented here. Their candidate IPC, gain and timing remain **unmeasured**.
Compare future changes against this table with identical settings; keep both
latency and throughput results, even when one regresses.

```sh
IPC_MEM=l2:5:70 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.StoreLoadDependencyBenchSpec'
JAVA_OPTS='-Xmx6G -Xms512M' make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast
```

All six baseline simulations passed. Local evidence:
`/tmp/store-load-prf-baseline.log`.

## Aligned-longword to subword forwarding — 2026-09-20

Candidate: `839e4322`, aligned-LONG producer forwarding based on `c41424f4`, option
`sqSubwordForwarding`; other latency options off. Same warmed 80-iteration
measurement, L2/DDR model and seeds 1/17 as above. Byte offsets 0–3 and word
offsets 0–2 are tested; registers start with nonzero upper bits so the independent
value checker covers partial-register preservation. Baseline/candidate differ
only by the forwarding option, not the program or window.

Matched run (64 configurations, all value checks passed, reproduced after
adding cycle-windowed forwarding-completion counters):

| Workload | Macros | Baseline cycles / IPC | Candidate cycles / IPC | IPC change |
| --- | --- | --- | --- | --- |
| One chain, byte or word reload | 400 | 1680 / 0.238095 | 880 / 0.454545 | +90.91% |
| Four chains, byte or word reload | 1120 | 5200 / 0.215385 | 1104 / 1.014493 | +371.01% |
| One chain, exact long reload | 400 | 880 / 0.454545 | 880 / 0.454545 | 0% |
| Four chains, exact long reload | 1120 | 1104 / 1.014493 | 1104 / 1.014493 | 0% |

Both seeds and every listed byte/word offset agree. This is a targeted
microbenchmark gain, not a Dhrystone or whole-system claim. The option removes
the drain wait for supported resident-SQ subword loads; it does not relax IQ
ordering or implement PRF forwarding. Full timing/area are **unmeasured**;
default remains off. Correctness: 34 SQ/split tests passed, including 1,152
randomized byte/word/long queries, wrap/flush/device/younger-overwrite checks,
and exclusion of split producers and independently translated split queries.
The initial new wrap test used a hard-coded ROB width and failed in its fixture;
it now derives the wrap from the actual port width.

The measured windows contain 80 actual forward completions for one chain and
319 for four chains with the option on; subword controls contain zero. These
are completion events within the retirement-cycle window, not an assertion
that each event belongs to a macro retiring within that same window.

Eight enabled-option oracle cases pass, including byte lanes, preserved upper
register bits, MOVEA.W sign extension, CCR, partial overwrites, split MOVEMs and
wrong-path inhibited loads. The new oracle case initially compared backing RAM
against dirty copyback data without evicting it; it failed with the option both
off and on. Four same-set stores now evict the target before checking memory;
both configurations pass without weakening register, CCR or memory checks.
The broader 104-configuration corpus passes with subword forwarding enabled,
including all four combinations of the earlier latency options. Every macro
and cycle count matches the previous disabled-option corpus exactly. The fast
gate passes: 381 tests passed, zero failed, two ignored (2026-09-21).

Evidence: `/tmp/sq-subword-final-unit.log`, `/tmp/sq-subword-final-ipc.log`,
`/tmp/sq-subword-oracle-baseline-fixed.log`, `/tmp/sq-subword-oracle-fixed.log`,
`/tmp/sq-subword-corpus.log` and `/tmp/sq-subword-fast.log`.

Matched baseline/subword routed timing is queued at pinned revision `839e4322`
in `/tmp/sq-forward-gate.jVqSkW/`, unit `m68k-sq-subword-839e4322.service`.
It waits for the existing latency matrix to finish, then uses the shared Vivado
mutex. Both arms use the same 5 ns OOC recipe and three post-route rounds;
results are pending. The existing `build-logs:0` window follows both jobs.

```sh
IPC_SQ_SUBWORD=1 IPC_MEM=l2:5:70 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.LsFallThroughIpcSpec'
LOCKSTEP_SQ_SUBWORD=1 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "subword forwarding" -z "partial-overlap" -z "overlapping sub-word" -z "MOVEM.L round trip" -z "spec-mmio D-side"'
```

Build monitoring uses the single tmux window `build-logs:0`; reuse it for future
build logs instead of creating per-build windows. Keep the active Codex window
separate and untouched.

## Next investigations requested — 2026-09-21

After the current LSU work, investigate branch prediction and a BOOM-style
point-of-no-return frontier. Neither has a new implementation or measured gain
in this entry.

- Branch prediction: use matched retired-branch windows, distinguish conditional
  direction errors from target/return errors and cold starts, and report both
  accuracy per branch and mispredictions per instruction. Aim for at least 95%
  on representative measured workloads, with IPC and routed Fmax comparisons.
- PNR: evaluate relaxing execution/resource constraints ahead of architectural
  retirement, not assuming arbitrary out-of-order architectural commit is safe.
  [BOOM's ROB documentation](https://docs.boom-core.org/en/latest/sections/reorder-buffer.html#point-of-no-return-pnr)
  describes a non-speculative frontier ahead of commit, used for coprocessor
  issue; its commit stage still retires in order and only authorizes stores to
  reach memory after commit. Audit our late memory/FPU faults, interrupts,
  debug recovery, 68040 macro boundaries and physical-register lifetimes before
  adopting analogous guarantees. Any true commit relaxation needs its own
  architecture amendment and precise-state proof.
