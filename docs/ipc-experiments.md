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
| Combined fall-through + early wake | See combined column and full-corpus ranges | Repaired implementation passes core OOC 200 MHz: WNS +0.002 ns, WHS +0.021 ns; default off pending integration. |
| Payload-selection timing repair, `b99636f4` | **0% change:** all 104 benchmark rows have identical macro and cycle counts before/after | Combined implementation now passes the matched core OOC gate; no isolated fall-through-only timing result claimed. |
| Macro-counter correction, `b0eeabaa` | Measurement correction, not an RTL speedup; removed extra call/return counts | Use corrected figures only. |
| Warm-window harness extension, based on `b99636f4` | **0% change:** all 104 existing benchmark rows unchanged with default warm-up 0 | Test-only change; new baseline below. |

Timing above is core out-of-context at 5 ns on `xcku5p-ffvb676-2-e`, not complete
SoC signoff. Hold and pulse width passed in the original fall-through run.
The repaired baseline/early-wake/combined matrix has completed.

Update, 2026-09-21: all three modes at pinned `b99636f4` have
finished. Baseline/early-wake routed resource counts
are respectively 93,896/94,283 LUTs (+387, about 0.41%), 38,740/38,727 FFs, and
37/37 BRAM tiles. Baseline setup/hold slack is +0.011/+0.023 ns; early-wake is
+0.030/+0.021 ns. Both have zero setup/hold/pulse-width failing endpoints and
+1.958 ns minimum pulse-width slack. These are matched core-only reports, not
board or SoC signoff. They demonstrate an IPC-positive early-wake candidate can
meet this 200 MHz gate with a small LUT increase; the 19 ps WNS difference is not
claimed as a reproducible Fmax improvement. Reports are `fullcore_route_timing.rpt`
and `fullcore_route_util.rpt` under the matrix's `baseline/synth` and
`earlywake/synth` directories.

Combined mode finished at 00:41 local: 92,814 LUTs (1,082 fewer than baseline,
about 1.15%), 38,733 FFs, 37 BRAM tiles. Setup/hold/pulse-width slack is
+0.002/+0.021/+1.958 ns, with zero failing endpoints. Report paths are under
`combined/synth`. The repaired combined candidate retains its measured IPC gains
while passing the core timing screen and reducing LUT use; the 2 ps setup margin
is very small and does not establish full-SoC closure or board correctness.
This is why the IPC-positive mechanism was retained after its original timing
failure instead of being abandoned.

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
It uses the shared Vivado mutex after the earlier latency matrix. Both arms use
the same 5 ns core-only recipe and three post-route rounds. Baseline has completed:
setup +11 ps, hold +23 ps, pulse +1.958 ns, zero failing endpoints; 93,896 LUTs,
38,740 FFs and 37 BRAM tiles. The subword arm subsequently passed: **+85 ps setup,
+23 ps hold, +1.958 ns pulse**, zero failures; **92,949 LUTs, 38,701 FFs, 37 BRAM
tiles**. That is 947 fewer LUTs and 39 fewer FFs in this matched whole-core route,
not an estimate from source lines. This is core-only validation, not full-SoC
closure or a measured board improvement. Combining it with other candidates
still needs integrated timing; independent margins/area gains do not add.
The existing `build-logs:0` window follows the serialized timing queue.

```sh
IPC_SQ_SUBWORD=1 IPC_MEM=l2:5:70 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.bench.LsFallThroughIpcSpec'
LOCKSTEP_SQ_SUBWORD=1 JAVA_OPTS='-Xmx6G -Xms512M' /home/qwertyoruiop/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "subword forwarding" -z "partial-overlap" -z "overlapping sub-word" -z "MOVEM.L round trip" -z "spec-mmio D-side"'
```

Build monitoring uses the single tmux window `build-logs:0`; reuse it for future
build logs instead of creating per-build windows. Keep the active Codex window
separate and untouched.

## Correct-branch dual retirement — 2026-09-21

Prototype based on `0ba42930`, default-off `pairCorrectBranch`, relaxes only the
slot-0 correct, macro-final branch barrier. Slot 1 must remain a non-branch and
pass all existing precise-state/cold-path gates. No extra predictor training
port or out-of-order architectural visibility is introduced. All earlier LSU
options are off in this experiment.

Matched warmed windows, seeds 1/17, L2 hit 5 cycles / DDR 70 cycles:

| Kernel | Macros | Baseline cycles | Paired cycles | Baseline → paired IPC |
| --- | ---: | ---: | ---: | --- |
| Hot loop | 336 | 252 | 252 | 1.333333 → 1.333333 |
| Alternating branch, seed 1 | 132 | 135 | 136 | 0.977778 → 0.970588 |
| Alternating branch, seed 17 | 132 | 234 | 235 | 0.564103 → 0.561702 |
| Deep backlog | 360 | 425 | 426 | 0.847059 → 0.845070 |
| Copyback call/return | 672 | 1342 | 1342 | 0.500745 → 0.500745 |
| Independent ALU | 396 | 267 | 267 | 1.483146 → 1.483146 |
| Alternating branch, long | 2112 | 2170 | 2169 | 0.973272 → 0.973721 |
| Deep backlog, long | 2520 | 2660 | 2661 | 0.947368 → 0.947012 |

Both seeds agree except where explicitly separated. The long alternating test
warms 128 of 512 iterations; the long backlog test warms 16 of 128. All 28
instrumentation-off/on controls have identical cycles and checked final values.
The option actually pairs 168 branches in the call/return window, 421 in the
long alternating window and 168 in the long backlog window. Yet the call/return
runtime is unchanged: exposed head-incomplete cycles rise from 671 to 755 as
pairing advances retirement into the next wait. Pair counts alone overstate the
opportunity.

The one-cycle differences are recorded, not rounded away: short alternating
windows begin one cycle earlier and finish on the same cycle; backlog windows
begin two cycles earlier and finish one earlier. The long alternating window
begins at the same cycle and finishes one earlier. These results do not establish
a meaningful steady-state throughput improvement. **Park this version, leave it
off by default, and do not queue synthesis ahead of IPC-positive candidates.**
This does not rule out wider/bulk retirement under a different bottleneck.

Branch misses are identical with the option off/on. Longer alternating windows
converge across seeds to 25/768 misses (96.745% accuracy), unlike the short window's
2/48 versus 11/48. Long backlog remains 29/224 (87.054%). These synthetic results
are not representative-workload branch-accuracy signoff.

Correctness: 18 directed off/on ROB simulations pass (correct/mispredict,
successor branch/fault/privilege/system/RTE, macro boundary, debug stop), including
one training event and final next-PC checks. Five enabled-option oracle tests
pass (BSR/RTS, interleaved redirects, stale BTB, RTD, conditional branch after RTE).
Fast gate passes: 382 tests, zero failures, two ignored. No synthesis or board gain is
claimed for this candidate.

Evidence: `/tmp/branch-pair-unit.log`, `/tmp/branch-pair-baseline-long.log`,
`/tmp/branch-pair-candidate-long.log`, `/tmp/branch-pair-oracle.log`,
`/tmp/branch-pair-fast.log`. Reproduce the profile with
`IPC_MEM=l2:5:70 sbt 'testOnly m68k040.bench.PipelineProfileSpec'`, then repeat
with `IPC_PAIR_BRANCH=1`. The printed `baselineCycles` is the instrumentation-off
control of the **same RTL option**, not the other RTL arm; compare separate logs.

## Branch-history and missing-training diagnosis — 2026-09-21

Simulation-only observations on the default RTL at `982e0b3d`, same seven kernels,
seeds 1/17 and warmed L2/DDR windows as above. No prediction mechanism changed.
Every macro/cycle count matches the prior disabled-pairing arm. New observations
are checked against instrumentation-off controls and separate retired-branch
denominators; the optional detailed event trace is bounded to 1,200 events/run.

Two distinct problems must not be conflated:

1. **Retained frontend history is overwritten.** In the long backlog window,
   all 28 repairs follow a new conditional-history emission after Tier 1. Tier 2
   keeps the refetched frontend (`earlySuppressFe`), but the delayed repair installs
   retirement-only history, omitting that retained emission. No same-edge shift
   is needed to reproduce the loss. For example, seed 1 emits a taken loop branch
   at cycle 244, keeps the frontend at 252, and repairs `ghr=3` to `ghrArch=0` at
   253. That branch trains at 260, so it was not discarded by Tier 2. This falsifies
   the old comment that refetch latency prevents a legitimate history/repair race.
   It does not establish an architectural-state failure or quantify the IPC gain
   from repairing it.
2. **Slot-1 conditionals can bypass direction prediction and never train it.**
   Long backlog's inner branch at `0x40800038` retires 112 times, taken 56 times,
   but only 28 instances carry PHT training metadata. All 28 misses lack it.
   Its loop branch trains all 112 instances and misses only the final exit.
   Aligner zeroes both prediction records; FetchAlign stamps only slot 0 and
   explicitly keeps slot 1's tag inert. Earlier comments claiming slot 1 still
   trained its carried PHT index were incorrect. This is a concrete admission
   limitation to investigate before simply enlarging the predictor.

Long alternating branch at `0x40800012` trains 274/384 instances, with 24 misses,
four untrained. Its loop branch trains 384/384 and misses once. Thus omitted
training alone does not explain all prediction failures. The long alternating
window has no repair-after-retained-shift events, unlike the backlog window.
Both seeds reproduce these counts. All results here are synthetic simulations,
not claims about the cause of every live-board misprediction.

Next controlled candidate: defer a slot-1 integer conditional branch to slot 0,
reusing the existing single prediction/training port. This may trade decode
pairing for fewer misses; measure both effects rather than assuming a win.
Do not restore the deleted wide speculative BTB lookup just to test the idea.
Separately, history recovery needs a retained-frontend contract (including older
redirect replacement and unrelated flushes); blindly repairing from architectural
history at Tier 1 would omit unresolved older branches and is not a valid fix.

Validation: all 28 matched profile/control runs pass; the required fast gate
passes 382 tests, zero failures, two ignored. The synthesized logic is unchanged
by this diagnostic pass; production-source edits correct misleading comments.
Evidence: `/tmp/branch-history-profile.log`, `/tmp/branch-history-training.log`,
`/tmp/branch-history-fast.log`.
Reproduce with `IPC_GHR_TRACE=deep-backlog-long-profile IPC_MEM=l2:5:70` and
`testOnly m68k040.bench.PipelineProfileSpec`. `history-window` counts are cycle-windowed;
`retired-branch-window` counts use macro ordinals; `GHR_EVENT`/`GHR_BRANCH` detail
includes warm-up and must not be reported as window-only counts.

## Single-port conditional admission — 2026-09-21

Default-off `deferSlot1Conditional`, based on `fbbdbcf8`. A slot-1 integer Bcc
uses the existing deferral path to become slot 0, where the current predictor
can supply both direction and training metadata. No second prediction/training
port, table or history checkpoint is added. This version does not repair the
retained-frontend history issue above. Other experimental options are off.

Matched warmed L2/DDR windows, seeds 1/17:

| Kernel | Macros | Baseline → candidate cycles | Baseline → candidate IPC | Branch misses |
| --- | ---: | --- | --- | --- |
| Hot loop | 336 | 252 → 252 | 1.333333 → 1.333333 | 1 → 1 / 84 |
| Alternating, seed 1 | 132 | 135 → 138 | 0.977778 → 0.956522 | 2 → 2 / 48 |
| Alternating, seed 17 | 132 | 234 → 138 | 0.564103 → 0.956522 | 11 → 2 / 48 |
| Deep backlog | 360 | 425 → 395 | 0.847059 → 0.911392 | 8 → 6 / 32 |
| Copyback call/return | 672 | 1342 → 1342 | 0.500745 → 0.500745 | 1 → 1 / 252 |
| Independent ALU | 396 | 267 → 267 | 1.483146 → 1.483146 | no branches |
| Alternating, long | 2112 | 2170 → 2304 | 0.973272 → 0.916667 | 25 → 1 / 768 |
| Deep backlog, long | 2520 | 2660 → 2285 | 0.947368 → 1.102845 | 29 → 4 / 224 |

Both seeds agree except where separated. Long backlog improves **16.41% IPC**
and reaches 98.214% accuracy. Long alternating reaches 99.870% accuracy but
**regresses 5.82% IPC**: admission serialization costs more than the saved misses.
Do not call accuracy alone a performance improvement or enable this globally
from these synthetic tests. The short-window seed-17 gain is warm-up-sensitive.

Combining with `pairCorrectBranch` does not recover the tight-loop regression:
long alternating remains 2304 cycles despite 192 actual branch pairs. Long
backlog becomes 2286 cycles (one more than admission alone), with 218 pairs.
Keep pairing off; no throughput benefit has been established for that combination.

Correctness so far: 38 directed frontend simulations pass (all 14 Bcc conditions,
word/long displacements crossing a cache line, BRA/BSR/non-branch controls, held
backpressure, exact PCs and words, both switch positions). Both 28-run profiled
experiments pass their instrumentation-off controls and final-value checks.
Seven enabled-option oracle cases pass: calls/returns, mispredict recovery,
BTB staleness, DBRA, MOVEM frontend resume, wrong-path inhibited loads and
post-RTE condition codes. The broader matched corpus passes all 104 configurations
per arm: 96 cycle counts are identical; eight hot-loop cases (four LSU option
combinations, two seeds) improve from 326 to 317 cycles, including startup.
The warmed hot-loop window above is unchanged. All macro counts match. The
earlier LSU-option gains remain intact; do not describe this cold-start difference
as a resident-loop throughput gain. The final fast gate passes: 382 tests,
zero failures, two ignored.

Evidence: `/tmp/branch-history-training.log` (baseline),
`/tmp/defer-conditional-ipc.log`, `/tmp/defer-conditional-pair-ipc.log`,
`/tmp/defer-conditional-unit.log`, `/tmp/defer-conditional-oracle.log`,
`/tmp/defer-conditional-corpus-baseline.log`,
`/tmp/defer-conditional-corpus-candidate.log`, `/tmp/defer-conditional-fast.log`.
Use `IPC_DEFER_CONDITIONAL=1` for the pipeline/LSU benchmarks,
`LOCKSTEP_DEFER_CONDITIONAL=1` for the oracle and `--defer-slot1-conditional`
for core synthesis. Defaults remain unchanged. Timing and board gains unverified.

Pinned candidate `1d50abbc` is queued for matched baseline/deferred core routing
in `/tmp/defer-conditional-gate.v4Jqc4`, systemd unit
`m68k-defer-conditional-1d50abbc.service`. It waits for the entire subword matrix,
then runs its two arms serially under the shared Vivado mutex, 5 ns constraint
and three post-route rounds. The existing `build-logs:0` tmux window follows
the active forwarding arm and this queued matrix. IPC development need not wait
for these timing results.

### Integration boundary and next LSU work

The experimental flags currently reach the full-core test/OOC generator only.
`M68kSocketTop` still instantiates default `LsEuPlugin`, `FetchAlignPlugin` and
`RobPlugin`; therefore **a normal SoC rebuild does not enable these candidates**.
Before board validation, expose explicit socket/build configuration, record it in
the SoC build identity, run socket/reset/translated-cache checks, and route the
integrated SoC. Do not substitute the separate core timing result for that work.

The fresh LSU corpus still exposes the intended dependency bottleneck: the
delayed-store/disjoint-load control spends 2,084 cycles with a ready younger load
blocked behind unready store data. Address operands and store data must become
independently ready; changing oldest-occupied LS selection to oldest-ready alone
remains unsafe. Current `LsEuPlugin` reads base/index/data together and captures
them into S1; actual SQ allocation happens later at P3 and also arbitrates the
shared completion port. A dispatch reservation by itself supplies neither an
address-disambiguation proof nor a guaranteed completion/data-publication slot.

The next integration must account for pending stores before SQ data publication,
retain independent address/data readiness, and let a denied load release/retry
its LSU resource so the older producer can progress. Preserve committed-store
identities across ROB wrap. Use conservative low-page-offset disjointness only
with proven access spans and serialization attributes; unknown/device/split
cases keep the existing ordering. The standalone tracker is still not a CPU
implementation, and no memory-reordering gain is claimed here.

## Slot-1 training and selective taken deferral — 2026-09-21

Two default-off arms based on `53bb53f7`:

- `trainSlot1Conditional` gives a co-emitted integer Bcc its implicit not-taken
  history/training record, using the sole prediction-tag/table-write port when
  slot 0 has no record. Conflicts defer slot 1. The secondary index comes through
  the Gshare-owned service, not the previously unwired index input.
- `deferTakenSlot1Conditional` additionally defers a secondary PHT-taken prediction
  to slot 0 for the existing target lookup. Not-taken branches retain pairing.
  This retains an additional PHT read that may be pruned in the baseline; no
  second BTB target lookup, tag allocator, history-shift port or training-write
  port is added. Route/area costs remain to be measured.

Same warmed macro windows, L2 hit 5 / DDR 70, seeds 1/17; all other experimental
options off. The table keeps cold-training sensitivity and losses visible:

| Kernel | Macros | Baseline cycles | Training-only cycles | Selective cycles |
| --- | ---: | ---: | ---: | ---: |
| Hot loop | 336 | 252 | 252 | 252 |
| Alternating, seed 1 | 132 | 135 | 135 | 138 |
| Alternating, seed 17 | 132 | 234 | 135 | 138 |
| Deep backlog | 360 | 425 | 395 | 395 |
| Copyback call/return | 672 | 1342 | 1342 | 1342 |
| Independent ALU | 396 | 267 | 267 | 267 |
| Alternating, long | 2112 | 2170 | 2112 | 2112 |
| Deep backlog, long | 2520 | 2660 | 2660 | 2285 |

Both seeds agree except where separated. Training-only fixes the long alternating
branch's missing metadata: all 384 instances train, zero inner-branch misses,
one loop-exit miss. IPC rises **0.973272 → 1.000000 (+2.75%)**, accuracy 99.870%.
But long backlog remains **0.947368 IPC**, 29/224 misses (87.054%) despite every
branch now training. Training coverage is necessary but not sufficient: this arm
still issues an implicit not-taken prediction when a slot-1 target is unavailable.

Selective deferral retains the alternating-loop result and improves long backlog
to **1.102845 IPC (+16.41%)**, 4/224 misses (98.214%). This removes the previous
all-deferral version's 5.82% warmed tight-loop regression. The shorter seed-1
alternating window still regresses **0.977778 → 0.956522 (−2.17%)**; do not claim
universal improvement or representative-workload accuracy signoff. The late
retained-frontend history repair is unchanged and still needs separate work.

Both profiled arms pass their matched instrumentation-off controls and register
checks. The updated profile explicitly requires complete training metadata for
every branch in the two long warmed windows. Simulation assertions require
one history event per emitted PHT record and no two tagged lanes per write port.
Directed decode tests exercise off/training/selective-not-taken/selective-taken
policies, moving nonzero indices, backpressure and 80 branches across the 63-tag
wrap; they compare the exact expanded record to the originating feed packet.
Both modes pass the ten recovery/oracle cases including I/D-side inhibited-memory
rules. The selective mode's broader LSU corpus passes all 104 matched rows:
96 are cycle-identical, while eight hot-loop startup cases improve 326 to 317
cycles (404 macros). These eight span both seeds and all four LS latency-option
combinations; warmed hot-loop timing remains unchanged. No rows are missing.

The added 80-iteration cracked-RMW/tag-wrap test initially failed its final-memory
check with both the baseline and selective modes. It incorrectly used the default
static source-line count as a dynamic retirement bound, stopping inside the loop
while the memory oracle ran to completion. The corrected explicit bound is
367 + alignment-padding macros. Baseline, training-only and selective modes now
all pass at each of three alignments, with the final memory check retained and
explicit loop-completion assertions. This was a test-window defect, not evidence
of a predictor-induced memory regression. The final four-policy metadata/tag-wrap
test also passes. The required fast-gate rerun after this test correction passes
all 382 tests (two ignored), with no failed or aborted suites.

Evidence: `/tmp/train-slot1-ipc.log`, `/tmp/train-slot1-checked-ipc.log`,
`/tmp/train-slot1-selective-ipc.log`, `/tmp/train-slot1-records.log`,
`/tmp/train-slot1-selective-records.log`, `/tmp/train-slot1-oracle.log`.
Further evidence: `/tmp/train-slot1-selective-corpus.log`,
`/tmp/train-slot1-rmw-baseline-short.log` (reproduced invalid-window failure),
`/tmp/train-slot1-rmw-{baseline,training,selective}-full.log`,
`/tmp/train-slot1-records-final.log`, `/tmp/train-slot1-fast-final.log`.
Use `IPC_TRAIN_SLOT1=1` or `IPC_DEFER_TAKEN_SLOT1=1` for the bench and the
corresponding `LOCKSTEP_...` variables for the oracle. Synthesis options are
`--train-slot1-conditional` and `--defer-taken-slot1-conditional`; the latter
enables the required training support. Both are distinct from unconditional
`--defer-slot1-conditional`. No board was halted, reset or loaded.

The baseline/training/selective core-only timing matrix is pinned to
`918b1a8e66f5a5da70e5190fe6948f06a0a0e7dd` and queued as
`m68k-slot1-training-918b1a8e.service`, artifacts
`/tmp/slot1-training-gate.yGdUUB`. It waits for the prior all-deferral matrix and
then uses the shared Vivado mutex, the same 5 ns recipe and three post-route
rounds. The existing `build-logs` tmux pane follows all three arms. No timing or
area result is claimed yet, and this queue does not block subsequent IPC work.

## Retained frontend history repair — 2026-09-21

Default-off `retainRedirectHistory`, based on `eeb60f14`, keeps the bounded
conditional-history suffix emitted after Tier-1 redirect when Tier 2 keeps that
frontend. It rebases the suffix on architectural history at the existing delayed
repair. Replacement redirects discard the old suffix; unrelated flushes and
invalidation retain the conservative behavior. No architectural checkpoint,
retirement port or PHT training write is added. The design contract is in
`ipc-design-options.md`.

Three matched arms isolate history alone, history plus slot-1 training, and
history plus selective taken deferral. Seeds 1/17, L2 hit 5 / DDR 70, the same
warmed macro windows and instrumentation-off controls as the previous entry:

| Kernel | Macros | Selective cycles | Selective + history cycles | Misses / branches, before → after |
| --- | ---: | ---: | ---: | --- |
| Hot loop | 336 | 252 | 252 | 1 → 1 / 84 |
| Alternating, short | 132 | 138 | 138 | 2 → 2 / 48 |
| Deep backlog, short | 360 | 395 | 380 | 6 → 5 / 32 |
| Copyback call/return | 672 | 1342 | 1342 | 1 → 1 / 252 |
| Independent ALU | 396 | 267 | 267 | no branches |
| Alternating, long | 2112 | 2112 | 2112 | 1 → 1 / 768 |
| Deep backlog, long | 2520 | 2285 | 2240 | 4 → 1 / 224 |

Both seeds agree. The incremental gains over selective deferral are **3.95%**
and **2.01% IPC** on the short and long backlog windows. Long-backlog IPC is
**1.125000**, up **18.75%** from the original 0.947368 baseline, with **99.554%**
branch accuracy. Other windows are unchanged versus selective deferral. Its
earlier short-alternating seed-1 regression versus the original baseline remains;
the experiment is not universally beneficial or representative-workload signoff.
History alone produces **no changed warmed cycle/miss counts**. History plus
training-only improves the short backlog from 395 to 365 cycles (**8.22% IPC**),
6 → 4 misses / 32 branches, on both seeds; its other twelve rows, including the
long backlog, are unchanged. Keep this short-window result separate from the
selective arm's sustained long-window gain.

All three 28-run profiled arms pass final-register checks and exact
instrumentation-off cycle comparisons. An independent event-stream model checks
the actual GHR cycle by cycle through full runs, without reading the helper's
suffix/count state. The long backlog requires observed retained repairs. Eleven
combined-option oracle cases pass, covering recovery, BTB staleness, DBRA,
calls/returns, cracked-RMW/tag wrap, MOVEM resume, wrong-path inhibited loads and
post-RTE condition codes.

The initial full-core test rejected an incorrect assertion that no history shift
could coincide with Tier 1: FetchAlign can still emit an old-path packet then,
while DecodeStage's flush discards it. The helper already gives a new epoch
priority over appending that event. The assertion was removed, the contract
clarified, and the directed test extended. A subsequent checker-only failure
read an unexposed simulator signal; the checker now observes the exposed source
signals, without changing hardware to satisfy the test. Neither failure is
reported as a passing run or as an architectural core failure.

Evidence: `/tmp/retained-history-checked-ipc-v2.log`,
`/tmp/retained-history-training-ipc.log`, `/tmp/retained-history-selective-ipc.log`,
`/tmp/retained-history-oracle.log`. The expanded directed test passes, including
same-edge redirect replacement/repair, saturation and discarded suffixes
(`/tmp/retained-history-unit-final.log`). All **104** broader LSU corpus rows
pass and are cycle- and macro-identical to the previous selective candidate,
across both seeds and all four load-latency option combinations
(`/tmp/retained-history-selective-corpus.log`). Both fast gates pass all
382 tests (two ignored, zero failed/aborted), including the final rerun in
`/tmp/retained-history-fast-final.log`. Use `IPC_RETAIN_HISTORY=1` in benchmarks,
`LOCKSTEP_RETAIN_HISTORY=1` in the oracle, or `--retain-redirect-history` in the
core generator. The option remains off in production; routed timing, integrated
SoC timing and board IPC are not established.

The incremental selective/retained core timing comparison is pinned to
`4c012cdcc691042ff0b44448dc1ec4c477064fb4`, unit
`m68k-retained-history-4c012cdc.service`, artifacts
`/tmp/retained-history-gate.EaS8V5`. It waits for the full slot-1 matrix, then
routes both arms serially under the shared Vivado mutex at 5 ns with three
post-route rounds. Both arms enable selective taken deferral; only the second
enables history preservation. The existing `build-logs:0` pane follows this
queue and its predecessors; the running Codex pane is untouched.

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
