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

The all-deferral matrix completed at 02:21 on 2026-09-21. Final route reports
(`{baseline,deferred}/synth/fullcore_route_{timing,util}.rpt`) show:

| Arm | Setup WNS | Hold WHS | Pulse WPWS | LUT | FF | BRAM tiles |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | +0.011 ns | +0.023 ns | +1.958 ns | 93,896 | 38,740 | 37 |
| All conditional deferral | +0.001 ns | +0.032 ns | +1.958 ns | 93,483 | 38,695 | 37 |

Both have zero setup, hold and pulse-width failing endpoints. This candidate
passes the core-only 200 MHz gate with only 1 ps setup margin and 413 fewer LUTs,
but retains its measured 5.82% warmed alternating-loop IPC regression. Keep it
as a measured comparison, not the preferred predictor policy. The selective
training matrix has now started; its results are separate and still pending.

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
rounds. The existing `build-logs` tmux pane follows all three arms. Baseline and
training-only have finished: WNS **+0.011 / +0.042 ns**, WHS **+0.023 / +0.023 ns**,
WPWS **+1.958 / +1.958 ns**, all zero setup/hold/pulse failing endpoints. Routed
LUTs **93,896 / 93,668**, FFs **38,740 / 38,770**, BRAM tiles **37 / 37**.
This is a core-only 200 MHz pass, not SoC timing or board-performance signoff.
Authoritative reports are each arm's `synth/fullcore_route_{timing,util}.rpt`.
The selective arm subsequently finished with WNS **−0.280 ns**, 1,418 setup-failing
endpoints (TNS −127.236 ns), WHS **+0.020 ns**, WPWS **+1.958 ns**, and no hold/pulse
failures. It uses 93,830 LUTs, 38,732 FFs and 37 BRAM tiles. The worst reported path
is ROB head → payload/FP-immediate reads → FPU unimplemented operand capture;
other reported families involve LS ownership/exception quiescence and frontend
quiesce/fetch-PC. This is not evidence that the PHT update itself needs a pipeline
cut. Preserve the IPC-positive candidate while investigating those actual cones;
the retained-history comparison now runs next and does not block IPC development.

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
core generator. The option remains off in production; integrated SoC timing and
board IPC are not established.

The incremental selective/retained core timing comparison is pinned to
`4c012cdcc691042ff0b44448dc1ec4c477064fb4`, unit
`m68k-retained-history-4c012cdc.service`, artifacts
`/tmp/retained-history-gate.EaS8V5`. It waited for the full slot-1 matrix, then
routed both arms serially under the shared Vivado mutex at 5 ns with three
post-route rounds. Both arms enable selective taken deferral; only the second
enables history preservation. The existing `build-logs:0` pane follows this
queue and its predecessors; the running Codex pane is untouched.

Completed 04:31 Europe/Rome: the selective baseline reproduces **−0.280 ns**
setup slack, while selective plus retained history **passes routed 200 MHz**:
WNS **+0.017 ns**, WHS **+0.021 ns**, WPWS **+1.958 ns**, zero setup/hold/pulse
failing endpoints. Routed LUTs 93,830→93,883; FFs 38,732→38,782; BRAM tiles
37→37. Reports are the respective `synth/fullcore_route_timing.rpt` and
`synth/fullcore_route_util.rpt`. This qualifies the measured retained-history
combination at the core gate, not the standalone selective option, a whole-SoC
build, or its as-yet-unrouted composition with the newer LSU candidates. The
different routed outcome does not prove a structural repair to the baseline's
cold FPU/exception/control cones; the final margin is only 17 ps. The serialized
queue has advanced to the early-store-address pair while IPC development continues.

## Memory-order ownership groundwork — 2026-09-21

Based on `13f9c850`; this advances proposal 12, not a load-bypass IPC candidate.
The table no longer derives age from wrapping ROB IDs relative to the live head.
One bit per unordered slot pair records allocation order; an eight-entry table
uses 28 order bits. Releasing a record does not reorder survivors, and reusing
a slot replaces its relations. There is no finite age-counter wrap assumption.
Ticket generation and drain-before-release rules are unchanged.

`MemoryOrderPlugin` is the sole table/service owner. The real `DispatchPlugin`
optionally includes table capacity in the atomic rename/ROB/IQ handshake,
compacting a lane-1-only memory operation without changing its ROB identity.
Directed testing uses real ROB and IQ plugins, not just a duplicate handshake
formula. It covers single/two-memory packets, an invalid second lane, full table,
one-free-slot/two-required refusal, non-memory admission with a full table,
flush cancellation, drained release, stale-ticket reuse, IQ-full and ROB-full.
Optional admission also qualifies detailed-perf acceptance counts; the existing
six-counter ABI does not gain a memory-stall category or mislabel it as IQ stall.
That extra reason must be accounted for before a full-core measured integration.

The first dispatch test compile used obsolete internal signal names; its test
observability now uses the public services. Running the previously existing
dispatch suite exposed a separate stale 64-entry ROB assumption: it expected
62 allocations from the current 32-entry ROB, which correctly stopped at 31.
The test now derives its bound from configured depth. The reservation test itself
passed before and after that correction. Evidence includes
`/tmp/memory-order-dispatch.log`, `/tmp/memory-order-dispatch-gates.log` (failures),
and `/tmp/memory-order-dispatch-gates-v2.log` (all seven tests passing).

Full-core control: with no memory-order plugin installed, all fourteen warmed
kernel/seed rows match the prior baseline exactly in macros, cycles and misses
(`/tmp/memory-order-disabled-ipc.log` versus `/tmp/branch-history-training.log`).
This verifies the disabled path only. There is **no enabled full-core bypass IPC,
timing, area or board result**. The expanded directed gates pass all twelve
tests, including 6,144 fixed-seed lifecycle transitions over 2/4/8-entry tables
and the detailed-counter admission checks
(`/tmp/memory-order-final-directed-v2.log`). The first eight-entry run exposed
testbench scheduling: its per-record query sweep could cross the next clock
edge before checking the next transaction. Widening the test clock period keeps
all combinational queries and next-input setup between active edges; no RTL or
expected-value weakening was needed. The failing run remains in
`/tmp/memory-order-final-directed.log`. The final full-core rerun again matches
all fourteen control rows exactly (`/tmp/memory-order-final-disabled-ipc.log`).
The required final fast gate passes **384 tests**, two ignored, zero failed or
aborted (`/tmp/memory-order-fast-final.log`).
No synthesis job is queued for this incomplete integration.

Still required for the actual throughput change: independently issue store
address/data, carry allocation tickets through delayed work, publish final
translated spans/attributes, release or retry denied loads without blocking the
older producer, and guarantee SQ/completion capacity before early wakeup. Do not
install the table in a CPU merely to accumulate reservations without those owners.

## Early store address, ordered late-data publication — 2026-09-21

Based on `8bedea22`; default-off `earlyStoreAddress` advances proposal 12 without
yet allowing younger loads to overtake a store. Ordinary register-to-memory MOVE
may start address calculation/translation while only its dynamic data operand
is outstanding. All address/static dependencies must be ready. The existing P3
token waits for a registered IQ readiness query, reuses the store-data PRF port,
and captures into its existing data/NZVC fields. Publication follows through the
normal SQ/completion arbitration. There is no extra PRF read port, data buffer,
early completion, or speculative architectural write. See the precise eligibility
and lifetime contract in [memory dependencies](memory-dependencies.md).

Matched initial corpus: `l2:5:70`, seeds 1/17, all four combinations of aligned
load fall-through and early load integer wakeup. Predictor experiments and SQ
subword forwarding are off. These are full-kernel windows including setup, not
the warmed predictor windows above. **112 pairs: 96 unchanged, 16 improved,
zero regressions**, identical macro counts. Twelve kernels are unchanged across
all modes/seeds. All 32 delayed stores in each of the two new delayed-producer
kernels actually use late capture; a test assertion prevents a silent fallback.

| Kernel | Load options | Macros | Baseline cycles (seed 1 / 17) | Candidate cycles (both seeds) | IPC gain |
| --- | --- | ---: | ---: | ---: | ---: |
| divide → store → load recurrence | neither / fall-through only | 98 | 2574 / 2572 | 2465 | 4.42% / 4.34% |
| divide → store → load recurrence | early wake / both | 98 | 2543 / 2541 | 2433 | 4.52% / 4.44% |
| delayed store + disjoint load | neither | 163 | 2163 / 2163 | 2161 | 0.093% |
| delayed store + disjoint load | fall-through / early wake | 163 | 2162 / 2162 | 2160 | 0.093% |
| delayed store + disjoint load | both | 163 | 2161 / 2161 | 2159 | 0.093% |

The divider-bound disjoint case gains only two total cycles; do not multiply that
by 32 stores or claim that early address issue provides load bypass. The recurrence
isolates a latency benefit but is not a Dhrystone or whole-system improvement.
Evidence: `/tmp/early-store-address-corpus-baseline.log` and
`/tmp/early-store-address-corpus-candidate.log`.

The additional short-source comparison also passes: **16 pairs, eight improved,
eight unchanged**, no regressions. All retire 130 macros, with identical final
register results. Rotate-fed recurrence costs 527 → 498/497 cycles without early
load wakeup (seeds 1/17), and 497/496 → 468/467 with it: **5.82–6.21% IPC gain**.
Fall-through does not change these figures. Every rotate-fed run observes 32 late
captures. Load-fed recurrence is unchanged: 731/730 cycles without early wakeup,
669/668 with it, and **zero late captures**. Thus this load-fed case does not prove
the new path improves common load→store chains; ordinary MOVE loads still crack
through an internal temporary and an ALU move. Do not attribute its unchanged
result to a tested late-capture latency. Evidence:
`/tmp/early-store-short-baseline.log` and `/tmp/early-store-short-candidate.log`.
Together the two comparisons cover **128 pairs: 24 improved, 104 unchanged**.
`IPC_KERNEL_REGEX='short-store-.*'` reproduces the additional comparison;
`IPC_KERNEL_REGEX` rejects an empty selection, and unset runs retain all kernels.

Directed IQ off/on tests pass dependency gating, held issue/skid identity under
compaction/backpressure, persistent readiness, flush and reused IDs
(`/tmp/early-store-address-iq.log`). New full-core oracle tests check DIV, shift
and load producers; byte/word/long stores including line crossing; flags;
inhibited versus copyback memory; and wrong-path stores behind branch recovery
(`/tmp/early-store-address-oracle.log`). The final RTL also passes nine selected
oracle tests with early load wakeup enabled, including both new cases, interleaved
redirects, write protection, nonresident page recovery, IRQ, speculative device
loads, divide-by-zero and split loads (`/tmp/early-store-address-recovery.log`).
These are selected gates, not a claim that every exception/store interleaving is
exhaustively tested.

The first elaboration rejected duplicate unconditional assignments to the capture
signal; compile-time enabled/disabled construction now gives it one driver.
The failed run remains `/tmp/early-store-address-ipc.log`; the repaired original
104-row corpus passed in `/tmp/early-store-address-ipc-v2.log`. A later timing-minded
cleanup removed a redundant live busy-query term from capture/issue arbitration:
the registered ready decision is irrevocable while this store owns its physical
source. A simulation assertion checks that contract. All 112 matched rows and
the nine recovery tests above use that final form.

Use `IPC_EARLY_STORE_ADDRESS=1`, `LOCKSTEP_EARLY_STORE_ADDRESS=1`, or the core
generator's `--early-store-address`. Production SocketTop remains unchanged.
Both required fast-gate runs pass **384 tests**, two ignored, zero failed/aborted
(`/tmp/early-store-address-fast.log`, `/tmp/early-store-address-fast-final.log`).
The implementation is pinned at `12c23962dcbba03218c49b2e33fa24dfba80aa48`.
Unit `m68k-early-store-address-12c23962.service` queues matched baseline/early arms
behind the retained-history comparison, using the shared Vivado mutex, 5 ns and
three post-route rounds. Both arms enable aligned-load fall-through and early
load integer wakeup; only the candidate enables early store address issue.
Artifacts: `/tmp/early-store-address-gate.sHxiPV`, followed by the existing
`build-logs:0` pane alongside the earlier jobs. No timing outcome is known yet;
no integrated SoC timing or board improvement is established. Full memory-order
ticket lifecycle, independent load retry/bypass, SQ reservation and guaranteed
early memory wakeup remain separate unfinished work.

## Direct longword MOVE loads — 2026-09-21

Based on `92859a1e`; default-off `fuseLongMoveLoads` removes the ordinary
`LOAD -> T0; MOVE T0 -> Dn/An` crack for non-auto-update longword MOVE loads.
It emits one LS micro-op with the original address operands and the final MOVE's
integer destination/flag declaration. Existing LSU forwarding, load completion,
translation and fault paths already support that shape: **no LSU datapath,
PRF port, queue or speculative state is added**. Byte/word merges, EA auto-update,
special operations and non-MOVE consumers remain unchanged. The design amendment
under proposal 7 spells out eligibility and precise-state requirements.

Initial full-core comparison uses the same 16 kernels, `l2:5:70`, seeds 1/17,
first-through-last macro windows and four load-latency option combinations as the
early-store experiment. Early store address, predictor experiments and subword
forwarding are off. Against the prior baseline's 128 rows, **84 improve, 38 are
unchanged, six regress**; all macro counts match. These are macro IPC results,
not an improvement manufactured by counting fewer micro-ops as instructions.

| Kernel | Macros | Baseline cycles, both load options (seed 1 / 17) | Fused cycles | IPC change |
| --- | ---: | ---: | ---: | ---: |
| pointer chain | 388 | 2436 / 2432 | 2172 / 2174 | +12.15% / +11.87% |
| load stream | 481 | 650 / 647 | 525 / 524 | +23.81% / +23.47% |
| same-line copyback | 244 | 335 / 334 | 304 / 303 | +10.20% / +10.23% |
| load/store, precise | 290 | 1599 / 1616 | 1498 / 1490 | +6.74% / +8.46% |
| mixed, precise | 326 | 792 / 798 | 808 / 798 | **−1.98%** / unchanged |
| store stream | 488 | 632 / 627 | 625 / 628 | +1.12% / **−0.159%** |
| mixed, copyback | 326 | 371 / 371 | 357 / 357 | +3.92% / +3.92% |
| short load→store recurrence | 130 | 669 / 668 | 607 / 606 | +10.21% / +10.23% |

Without either older load option, load-stream IPC improves **37.62–37.91%** and
pointer-chain IPC **9.83–9.87%**. The precise mixed seed-1 loss repeats in all four
modes; the one-cycle store-stream seed-17 loss occurs in the two early-wakeup
modes. No loss is omitted from the six-regression count. Exact rows are in
`/tmp/fused-long-move-ipc.log`, compared with
`/tmp/early-store-address-corpus-baseline.log` and
`/tmp/early-store-short-baseline.log`. The fresh disabled 128-row rerun
(`/tmp/fused-long-move-disabled-ipc.log`) exactly matches those original macro
counts and cycles. Pointer-chain demand spacing falls from nine to eight cycles
with both older load options (253 intervals at each dominant spacing); this is
the measured recurrence interval, not an isolated cache-pipeline latency.

Fusion plus early store address also passes all 128 rows
(`/tmp/fused-long-move-combined-ipc.log`): versus fusion alone, **32 improve and
96 are unchanged, with no additional regressions**. Versus the disabled baseline,
the same 84 improve, 38 stay equal and six regress. With both older load options:

| Recurrence | Macros | Disabled cycles (seed 1 / 17) | Fusion only | Fusion + early store address | Combined IPC gain over disabled |
| --- | ---: | ---: | ---: | ---: | ---: |
| divide → store → load | 98 | 2543 / 2541 | 2541 / 2539 | 2431 / 2431 | +4.61% / +4.52% |
| rotate → store → load | 130 | 497 / 496 | 467 / 466 | 377 / 376 | +31.83% / +31.91% |
| load → store recurrence | 130 | 669 / 668 | 607 / 606 | 406 / 405 | +64.78% / +64.94% |

Both short recurrences now record 64 late-data captures (two stores per iteration).
Removing the intervening ALU copy exposes the load-produced dynamic dependency
to early store address execution; early address alone recorded zero late captures
in the load-fed recurrence. These are small synthetic chains, not Dhrystone gains.

Mixed-case cycle profiling reproduces the original measurements exactly
(`/tmp/fused-long-move-mixed-baseline.log`,
`/tmp/fused-long-move-mixed-candidate.log`). Both retire 326 macros and 40 branches,
with **zero branch misses** inside the window. Fusion removes 40 micro-ops
(366 → 326), and reduces mean ROB occupancy from about 17.0 to 14.9. For seed 1,
head-incomplete/no-retirement cycles increase 549 → 565, accounting for the
16-cycle regression; seed 17 stays at 555. All head-incomplete samples occur at
the repeated load PCs. Neither arm records retirement-pair capacity stalls.
This localizes the observed loss to load completion waiting, not branch recovery
or a need for wider retire; it does not yet identify the cause of the changed
memory-service schedule. Keep the regression visible pending that investigation.

The directed decoder test covers 448 eligible source/destination combinations,
the same combinations with fetch faults, and bit-identical excluded byte/word,
auto-update, register/immediate, memory-destination, non-MOVE and privileged forms
(`/tmp/fused-long-move-decode-v2.log`). Its first compile had test-only missing
type qualification and enum-driver errors (`/tmp/fused-long-move-decode.log`),
fixed without changing production bundles.

Twenty-five selected oracle tests pass with fusion plus both load-latency options
(`/tmp/fused-long-move-oracle.log`), including direct data/NZVC/alias cases,
inhibited/copyback memory with delayed reads, split loads, indexed forms, redirects,
IRQ/CCR recovery, page/store-fault recovery and wrong-path device reads. Four
new/extended tests also pass with fusion off, and six pass with fusion **plus
early store address and both load options**: delayed store data/squash, direct
load values/flags/aliasing, nonadjacent physical-page splits, and both nonresident
load/store fault → mapping handler → RTE → retry
(`/tmp/fused-long-move-oracle-control-v2.log`,
`/tmp/fused-long-move-combined-oracle-v2.log`). The separate direct A7 load → trap
entry → RTE case passes both disabled and combined configurations
(`/tmp/fused-long-move-a7-control.log`, `/tmp/fused-long-move-a7-enabled.log`).

The new faulting-load check initially diverged with fusion both enabled and
disabled. Root cause was the existing fixture: the oracle's missing leaf defaulted
to `0xffffffff`, a **resident, write-protected** page, whereas RTL explicitly got
zero/nonresident. The old store case happened to fault for the wrong reason;
the load correctly did not fault in that mismatched oracle setup. Explicitly
preloading the oracle leaf to zero fixes the premise, without relaxing frame,
destination, flag or retry assertions. Preserve the failed evidence in
`/tmp/fused-long-move-combined-oracle.log` and
`/tmp/fused-long-move-oracle-control.log`; the standalone oracle reproduction is
`/tmp/fused-load-fault-repro.Wwmclb`.

Both required fast gates pass 384 tests, two ignored, zero failed/aborted
(`/tmp/fused-long-move-fast.log`, `/tmp/fused-long-move-fast-final.log`).
Implementation: `387bd3fc3f98521cfbb28060e0180ef8151b8fd9`. Matched routed
timing is queued as `m68k-fused-long-move-387bd3fc.service`, behind the early-store
comparison, using the shared Vivado mutex, 5 ns and three post-route rounds.
Both arms enable aligned-load fall-through and early integer wakeup; only the
candidate enables direct longword MOVE loads. Early store address is off here
to isolate fusion. Artifacts: `/tmp/fused-long-move-gate.5pIIgG`; the existing
`build-logs:0` pane follows both elaboration and implementation logs. The matched
core-only run has now finished: baseline **+0.017 ns**, fusion **−0.275 ns** setup;
fusion has TNS −100.626 ns across 936 failing setup endpoints, hold **+0.023 ns**
and pulse **+1.958 ns**, with no hold/pulse failures. Routed LUTs decrease
94,244→93,504 and FFs 38,751→38,693; the setup miss is not evidence of higher
total utilization. The worst reported family is LS aligned-descriptor count →
probe arbitration/read-slot logic → D-cache BRAM address (16 logic levels,
74.0% routing delay). Other near-worst paths are ROB head → FPU unimplemented
operand capture and ALU result bypass → LS index capture. Preserve the positive
IPC version. Repair candidates are early registered admission/slot selection
with explicit consume-and-replace ownership, and a registered cold FPU operand
capture phase; do not add a resident-load recurrence cycle without remeasuring.
These are proposed repairs, not implemented or verified fixes. The combined
fusion/early-store configuration still needs its own timing gate. The serialized
SQ-reservation comparison has started next.
Use `IPC_FUSE_LONG_MOVE_LOADS=1`, `LOCKSTEP_FUSE_LONG_MOVE_LOADS=1`, or
`--fuse-long-move-loads`. Production SocketTop remains unchanged. No board was
halted, reset or reloaded; no board IPC or SoC timing gain is claimed.

## Early SQ reservation for late store data — 2026-09-21

Based on `a086a37c`. Default-off `reserveLateStore` gives the already-translated
P3 fast store a real SQ slot before its operand arrives. Address/attributes use
the existing allocation port and data uses the existing SQ array. Eight data-ready
bits, a three-bit owner slot and one owner-valid bit distinguish reservation from
publication. An unfilled entry consumes capacity, participates in overlap/age
selection, but cannot forward stale array contents or drain. No additional PRF
read port or store-data buffer is introduced.

The qualified late-data capture writes directly to the reserved entry and may
complete the store on that edge, eliminating the intervening P3-data staging
cycle. Completion collision still holds P3; its existing data/NZVC fields retain
the capture, and later completion never allocates/publishes again. Flush cancels
the synchronous P3 owner and its uncommitted reservation together. If SQ capacity
first appears on the capture edge, retain the ordinary captured-data allocation
path: reserving then would leave no subsequent late-capture event to fill it.
The registered readiness qualifier is cleared on departure so a replacing P3
store cannot inherit its predecessor's ready decision. Precise/inhibited and
privilege-blocked stores retain the original path.

This is a **P3-owned reservation**, not dispatch SQ allocation, independent load
retry or a guaranteed early memory wakeup. The shared completion port is not
reserved ahead, so no wake promise is announced. The full memory-order ticket
lifecycle/bypass work remains incomplete; see the amendment in
[memory dependencies](memory-dependencies.md).

Matched full-core comparison enables direct long MOVE loads and early store
address in both arms. It retains the same 16 kernels, `l2:5:70`, seeds 1/17,
first-to-last macro windows and four older load-option combinations. Against
`/tmp/fused-long-move-combined-ipc.log`, all **128 macro counts match: 32 improve,
96 are unchanged, no additional regressions**. The six prior fusion regressions
against the original baseline remain; do not erase them from the cumulative
assessment. Candidate: `/tmp/sq-reserve-corpus.log`. The fresh disabled rerun
(`/tmp/sq-reserve-disabled-corpus.log`) passes and reproduces all 128 previous
macro counts and cycle counts exactly. With both older load options:

| Kernel | Macros | Before cycles (seed 1 / 17) | Reserved cycles | Incremental IPC gain |
| --- | ---: | ---: | ---: | ---: |
| divide → store → load recurrence | 98 | 2431 / 2431 | 2399 / 2399 | +1.33% / +1.33% |
| rotate → store → load recurrence | 130 | 377 / 376 | 339 / 338 | +11.21% / +11.24% |
| load → store recurrence | 130 | 406 / 405 | 347 / 346 | +17.00% / +17.05% |
| delayed store + disjoint load | 163 | 2157 / 2158 | 2156 / 2157 | about +0.046% |

The divider-bound disjoint case saves only one total cycle, not one per store.
Every delayed-producer run records 32 reservations/publications; every short
recurrence records 64. Test assertions match those against actual captures.
These figures describe synthetic recurrence/throughput tests, not board Dhrystone.

Directed SQ tests pass 256 full-capacity reservations with pseudorandom fill data,
youngest-unfilled overlap suppression including subwords, simultaneous fill and
allocation, same-edge flush/publication, committed preservation, owner cancellation,
tail reuse and subsequent ordered drain (`/tmp/sq-reserve-directed-v2.log`).
The first attempt used the existing single-command drain-ack helper, which misses
adjacent pipelined commands, and was terminated while waiting for the missing
ack (`/tmp/sq-reserve-directed.log`). The new test counts every accepted command
and checks drain completion within 100 cycles; no RTL was relaxed for this fix.

Thirteen selected combined-option oracle tests pass
(`/tmp/sq-reserve-oracle-v2.log`), including a full SQ while late data arrives,
redirect cancellation of an **observed unfilled reservation**, flags/byte lanes,
split accesses, page-fault mapping/RTE/retry, A7 trap/RTE and wrong-path MMIO.
The data/CCR test observes 21 reservations and 21 publications in copyback mode,
one reserved completion-hold cycle, and zero reservations in inhibited mode.
The first oracle invocation and its queued control failed to compile because a
test referenced the existing flush signal under `logic` instead of its actual
plugin-level location (`/tmp/sq-reserve-oracle.log`,
`/tmp/sq-reserve-full-squash-control.log`); only that test reference was corrected.
The two new full-capacity/redirect oracle controls also pass with reservation off
(`/tmp/sq-reserve-full-squash-control-v2.log`). The required fast gate passes
**384 tests**, two ignored, zero failed/aborted (`/tmp/sq-reserve-fast.log`). The
broader SQ and split-SQ regressions pass **36 tests**, including the two new
reservation tests (`/tmp/sq-reserve-regression.log`). Use
`IPC_RESERVE_LATE_STORE=1`, `LOCKSTEP_RESERVE_LATE_STORE=1`, or
`--reserve-late-store`, together with early store address execution. Production
defaults remain off; no board improvement is claimed.

Implementation: `6d8a7a7de491f1e9a10ff74dabc33e67a2411571`. Unit
`m68k-sq-reserve-6d8a7a7d.service` queues matched core routing behind direct-load
fusion, using the shared Vivado mutex, 5 ns and three post-route rounds. Both
arms enable aligned-load fall-through, early integer wakeup, early store address
and direct long MOVE loads; only the candidate enables late-store SQ reservation.
Thus the baseline also gates the previously untimed fusion/early-store combination.
Artifacts: `/tmp/sq-reserve-gate.sU6Lpv`; the existing `build-logs:0` pane follows
both elaboration and implementation logs. This queue does not stall the next IPC
experiment.

Completed routing: the combined baseline misses setup at **−0.169 ns**
(TNS −19.043 ns, 278 endpoints); the reserved candidate meets **+0.050 ns**
setup, **+0.010 ns** hold and **+1.958 ns** pulse width, zero failing endpoints.
Routed LUTs are 93,618 → 94,078, FFs 38,762 → 38,779, BRAM tiles 37 → 37.
This qualifies the pinned reservation combination at core-only 200 MHz; it does
not establish that reservation alone repairs a specific critical path, nor
qualify later detached/publication/retirement changes or the integrated SoC.
The serial queue has advanced to the detached-owner comparison.

Follow-on dependency experiment (now implemented below): move late-data ownership from P3 into
the reserved SQ entry, reusing its stored address/data and adding only the source
tag and minimal store-completion metadata. An independent late-data reader could
then fill/complete the store while P3 serves younger known-disjoint loads. Keep
oldest-LS address issue initially, so no load overtakes an unknown older address;
known overlap with unavailable data remains a real wait. This could avoid a
duplicate address table for that subset, but requires a spec amendment, a precise
completion/flush/lifetime design and actual bypass/liveness tests. It is not
part of the P3-owned implementation or a substitute for evaluating the remaining broader dependency,
reservation-backed wakeup and retry requirements.

## Independent late-store ownership and known-address load bypass — 2026-09-21

Default-off `detachLateStore`, layered on early-store-address execution and SQ
reservation. The [memory-dependency specification](memory-dependencies.md) now
permits this bounded alternative to duplicating dispatch address metadata: keep
oldest-LS address issue, translate the older store fully, reserve its SQ entry,
then let P3 serve younger accesses while one independent owner waits for data.
Only source/completion metadata and four retained NZVC bits leave P3; no extra
PRF port, duplicate address table or store-data buffer is added. A second pending
store waits conservatively. Full/partial/physical-alias overlaps still consult
the SQ, and inhibited accesses retain their existing ordering/commit rules.

The owner fills SQ on its qualified PRF read edge even if completion loses to
an already-launched cache response or precise-store replay. It retains NZVC and
completes later, without publishing twice. New front completions yield to this
owner, avoiding a deadlock when a younger overlapping load occupies P4. Flush
cancels owner and reservation together; readiness is not inherited on reuse.
This is actual known-address load bypass, **not** unknown-address speculation,
general oldest-ready LS selection, an integrated dispatch tracker, or a guaranteed
advance wakeup. Production defaults stay off.

Matched full-core comparison: P3 reservation baseline versus detached owner,
both with direct long MOVE fusion and early store address, `l2:5:70`, seeds 1/17,
and four combinations of the older aligned-load fall-through/early-wakeup options.
The corpus now has 17 kernels, adding a recurrence where a disjoint younger load
feeds the next divide. All **136 macro counts match: 28 improve, 108 are identical,
zero new regressions**. All 128 pre-existing baseline rows reproduce the prior
P3-reservation run exactly. The earlier direct-load-fusion regressions against the
original baseline remain part of the cumulative assessment.

| Kernel, both older load options | Macros | P3-owner cycles (seed 1 / 17) | Detached cycles | Incremental IPC gain |
| --- | ---: | ---: | ---: | ---: |
| delayed store / disjoint-load recurrence | 100 | 2559 / 2558 | 2245 / 2244 | +13.987% / +13.993% |
| delayed store + disjoint load | 163 | 2156 / 2157 | 2151 / 2152 | +0.232% / +0.232% |
| rotate → store → load recurrence | 130 | 339 / 338 | 333 / 333 | +1.802% / +1.502% |
| load → store recurrence | 130 | 347 / 346 | 343 / 342 | +1.166% / +1.170% |

Without aligned-load fall-through the new disjoint recurrence goes 2591→2245
cycles (+15.412% IPC). Each such run observes **32 younger load completions before
their older detached stores capture data**; the older disjoint kernel observes
31. Instrumentation compares both live ROB IDs relative to head, not a signed
half-range subtraction. No board-speedup claim follows from these synthetic tests.

Logs: `/tmp/sq-detach-baseline-ipc.log` and
`/tmp/sq-detach-candidate-ipc.log`. Reproduce with `IPC_RESERVE_LATE_STORE=1
IPC_EARLY_STORE_ADDRESS=1 IPC_FUSE_LONG_MOVE_LOADS=1 IPC_MEM=l2:5:70` and
`testOnly m68k040.bench.LsFallThroughIpcSpec`; add `IPC_DETACH_LATE_STORE=1`
only to the candidate. The initial directed attempt failed to compile because
the new context used an unqualified `Size()`; qualifying the existing enum fixed
that test/build error (`/tmp/sq-detach-initial-ipc.log`). Its rerun passed all 32
selected windows (`/tmp/sq-detach-initial-ipc-v2.log`), before the full comparison.

Fourteen selected combined-option oracle tests already pass
(`/tmp/sq-detach-oracle.log`): data/CCR/subwords, aliases, split physical pages,
device ordering, observed unfilled-reservation redirect cancellation, precise
page faults/retry, direct-load/A7 trap handling and wrong-path MMIO. The alias test
maps two virtual pages to the same physical page in **both** RTL and Musashi;
the new optional oracle-MMU argument avoids treating virtual aliases as disjoint
oracle storage. The split case wraps its second physical fragment to the start
of that same page. The device case observes the younger inhibited load parked
behind the owner and exactly one eventual device read.

The full-capacity/different-source test passes and asserts both owner capture
with all eight SQ slots occupied and another pending source waiting in P3.
The first contention test failed its coverage assertion: only three detached
publications occurred and none collided with a back response. No data mismatch
was reported (`/tmp/sq-detach-oracle-capacity.log`); no RTL was changed for this
failure. Repeating warm loops at different phases exercised two collisions and
recoveries. That run passes **53** selected oracle tests, including IRQ, NMI,
nested interrupts, varied precise-drain response timing and write-protect faults
(`/tmp/sq-detach-oracle-capacity-v2.log`); the four new P3-owner controls also pass
(`/tmp/sq-detach-oracle-control-v2.log`).

The final contention fixture uses `0x80000000 / 0xffff`, producing packed
`0x80008000`, so a held completion must retain a nonzero N flag. It observes
**51 publications, three contended captures and three retained completions**,
checking each resumed ROB identity and NZVC=8 as well as architectural oracle
results. All four new tests pass with detach enabled and disabled
(`/tmp/sq-detach-oracle-capacity-v3.log`,
`/tmp/sq-detach-oracle-control-v3.log`). The required fast gate passes **384**
tests, two ignored, no failed or aborted suites; the exact final-source rerun
also passes (`/tmp/sq-detach-final-fast-v3.log`). Timing/area remain pending, with a
matched core routing pair to follow the P3-reservation pair.

Implementation: `876e58f53693dba771204946ef2bb74980ae0710`. Unit
`m68k-sq-detach-876e58f5.service` is queued behind P3 reservation, with shared
Vivado mutex, 5 ns and three post-route rounds. Both pinned arms enable aligned
load fall-through, early integer wakeup, direct long MOVE fusion, early store
address and late-store SQ reservation; only the candidate adds
`--detach-late-store`. Artifacts: `/tmp/sq-detach-gate.97VroC`. The existing
`build-logs:0` pane follows both arms' elaboration and implementation logs, along
with its previous queue. No board reset, halt, reload or measurement was performed.

Follow-on dependency-latency experiment (implemented below): let a waiting P4 overlap query use **actual
same-edge SQ publication** rather than waiting for `dataReady` to register and
then re-querying. Preserve youngest-overlap priority and byte masks; do not let
a younger partial match expose older data, or bypass device/flush rules. This
would be publication-edge forwarding, not an advance wake guarantee. Its likely
timing risk is PRF data → SQ selection → P4; investigate registered address-match
preselection if necessary without restoring the cycle it tries to remove. Keep
the single-owner capacity limitation and broader retry work visible.

## Publication-edge forwarding and SQ generation-safe winner selection — 2026-09-21

Default-off `forwardOnPublish` requires SQ reservation. A matching live unfilled
entry can satisfy the forwarding query on its **actual** publication edge, so a
waiting P4 load registers the new verdict/data without an extra data-ready/re-query
cycle. The common publication-data mux is placed after the existing winner tree,
then uses normal big-endian extraction; there is no new storage or PRF port.
Publication identity, residency and flush qualify availability. Youngest overlap,
partial/split hazards and inhibited serialization remain mandatory. This is not
an advance wake promise; completion still arbitrates normally.

This review found a separate pre-existing correctness defect in winner selection.
`ent` correctly recognized committed entries as older than any live load, but the
winner tree then ranked those entries by wrapping `query.robId - store.robId`.
An old committed-but-undrained store could survive ROB reuse and appear closer to
the query than a genuinely younger overwrite. Two directed reproducers fail with
the old selection (`/tmp/sq-committed-reuse-repro.log`): the ordinary path forwards
old data over a newer ready store, and reservation mode forwards old data through
a newer **unfilled** store. The ordinary reproducer has all optional features off;
this is not caused by publication forwarding. There is no evidence attributing
any previous board crash to this defect.

The fix ranks eligible matches by existing SQ allocation position `(slot-head)`,
selecting the greatest rank. Allocation and drain are FIFO ordered, so this stays
correct across ROB generations and physical SQ wrap, without extra age state.
The rank shrinks from five bits to three for the eight-entry queue. Live-load age
eligibility is unchanged. The spec explicitly retains ordered SQ allocation;
future out-of-order allocation would need a different age rule.

All **42 SQ/split tests pass** (`/tmp/sq-publish-regression.log`). New coverage
includes both formerly failing stale-generation cases, 64 seeded sequences that
exercise every physical SQ head position, full/partial/unfilled younger matches,
and 256 random byte/word/long publication checks in each enabled/disabled arm.
The latter checks before the actual clock edge, verifies big-endian data, crosses
ROB index zero, and tests inhibited queries. Additional cases cover younger
ready/partial/unfilled overrides, independently translated split overlap and
same-edge flush cancellation. The original three publication tests also pass
before the rank fix (`/tmp/sq-publish-directed.log`).

The first 136-case publication comparison, before the independent rank repair,
has **24 improvements, 112 identical results, no regressions** versus detached
ownership: `/tmp/sq-publish-candidate-ipc.log` versus
`/tmp/sq-detach-candidate-ipc.log`. The repaired baseline now passes all 136 cases
and exactly reproduces the previous detached-owner macros/cycles
(`/tmp/sq-publish-baseline-ipc.log`): the correctness fix has zero measured IPC
effect in this corpus. The final repaired candidate also passes and reproduces
all 136 pre-repair candidate macro/cycle counts exactly
(`/tmp/sq-publish-candidate-ipc-v2.log`). Thus the final matched comparison still
has **24 improvements, 112 unchanged, no new regressions**. The earlier fusion
regressions against the original baseline remain in the cumulative assessment.
The expanded combined-option oracle run passes **65 tests**
(`/tmp/sq-publish-oracle.log`), including a CPU slow-write test for misleading
committed ROB generations. That test varies 0–31 NOPs between two same-address
WT stores with 1000-cycle memory response latency, observes a live query where
the old rank would select the wrong generation, and checks all architectural
registers/flags against Musashi. Other cases cover physical aliases, split pages,
full SQ, source handoff, retained nonzero flags across completion contention,
redirect cancellation, device ordering, IRQ/NMI, precise-drain races and page
faults/retry. The same 32-program generation test also passes with **all optional
features off**, again observing a misleading old rank
(`/tmp/sq-generation-default-oracle.log`). All six subword-composition oracle
controls also pass (`/tmp/sq-publish-subword-oracle.log`), retaining the three
observed completion-contention/recovery events with NZVC=8.

Final incremental results with both older load optimizations:

| Kernel | Macros | Detached cycles (seed 1 / 17) | Publication cycles | IPC gain |
| --- | ---: | ---: | ---: | ---: |
| divide → store → load recurrence | 98 | 2399 / 2399 | 2367 / 2367 | +1.352% |
| rotate → store → load recurrence | 130 | 333 / 333 | 304 / 304 | +9.539% |
| load → store recurrence | 130 | 343 / 342 | 286 / 285 | +19.930% / +20.000% |

Those runs observe 32, 31 and 61 actual publication-edge forwarding hits,
respectively. The disjoint-load and non-store kernels are unchanged. These are
matched synthetic first-to-last macro windows (`l2:5:70`, seeds 1/17, four older
load-option combinations), not a board claim. Use `IPC_FORWARD_ON_PUBLISH=1`,
`LOCKSTEP_FORWARD_ON_PUBLISH=1`, or `--forward-on-publish` alongside reservation;
the measured combination also enables detached ownership, direct long MOVE
fusion and early store address. The required fast gate passes **384 tests**, two
ignored, no failures/aborted suites (`/tmp/sq-publish-fast.log`). Routed timing
remains pending. Retain the candidate if timing needs repair rather than
discarding the measured latency gain.

Implementation: `3f53a39be5e5e3b9440c33bebc09c3bc6f945ab1`. Unit
`m68k-sq-publish-3f53a39b.service` queues two pinned core routing arms behind the
detached-owner pair, under the shared mutex at 5 ns with three post-route rounds.
Both arms include the generation-safe SQ winner fix and all measured detached
owner prerequisites; only the candidate enables `--forward-on-publish`. The
preceding pinned detached run supplies the pre-fix timing/area reference for the
corrected baseline. Earlier pinned runs remain useful measurements, but final
integration must include this correctness fix. Artifacts:
`/tmp/sq-publish-gate.GHXuam`; existing `build-logs:0` follows both arms and the
prior queue. No board intervention or board IPC measurement was performed.

Next simple latency question: when an owner is reserved, it currently discards
the source-readiness qualification being observed for that very P3 store. If the
source becomes ready during address resolution, the new owner may wait an extra
cycle merely to restart the same registered qualification. Test safe transfer of
that decision on admission, using the same source tag and existing register,
without adding speculative readiness or another PRF port. Also quantify how often
one pending owner blocks another ready address before expanding owner capacity.

## Store readiness on owner admission — 2026-09-21 (not retained)

Tested transferring the existing source-readiness query into `readyPrior` when
the detached owner reserves its slot, instead of clearing that register. This
uses the same source tag and registered qualification, with no extra port/state.
All **136 matched macro/cycle counts are identical** to the publication-forwarding
candidate: no IPC gain and no regression. The experiment was removed; admission
still clears `readyPrior`. No routing job is warranted for this zero-gain change.

Evidence: `/tmp/sq-admission-ipc.log`,
`/tmp/sq-admission-instrumented-ipc.log`, and
`/tmp/sq-admission-restored-ipc.log` each reproduce every macro/cycle count in
`/tmp/sq-publish-candidate-ipc-v2.log`. The prototype passes 65 combined-option
oracle tests, six subword-composition controls and the 384-test fast gate
(`/tmp/sq-admission-oracle.log`, `/tmp/sq-admission-subword-oracle.log`,
`/tmp/sq-admission-fast.log`). The early-data copyback oracle exercises six
ready-on-admission events and checks their next-cycle captures. After restoring
the original qualification, all eight targeted reservation/detachment oracle
tests pass (`/tmp/sq-admission-restored-oracle.log`).

Retained diagnostics count ready admissions and cycles with a pending P3 store
behind an occupied owner, including overlap with P4 forwarding stalls and SQ
fullness. These are overlapping observations, not exclusive stall attribution.
With both older load optimizations, the rotate recurrence sees only one ready
admission per window; the load-fed recurrence sees none. All 60 owner-wait cycles
in the load-fed case also have a P4 forwarding stall. The divide recurrence's
2224–2225 owner-wait cycles likewise overlap P4 stalls. The disjoint recurrence
has 2106–2107 owner-wait cycles without that overlap, but this alone does not
prove a second owner would improve IPC: the next source may still depend on the
current recurrence. Keep the source-identity assertion, but require an actual
matched benefit before adding capacity.

## Composed LSU / prediction / retirement profiles — 2026-09-21

Re-ran the seven pipeline-profile kernels at seeds 1/17 with `l2:5:70`, using
exact warmed macro windows and an instrumentation-disabled matched control for
every run. Four configurations, 28 runs each, pass the same register-result,
branch-denominator and instrumentation-cycle checks. The test now accepts the
existing LSU feature flags and reports occupancy and completed-prefix histograms.
This is a composition measurement, not a new RTL optimization.

`LSU` enables fall-through, early integer wake, direct long MOVE load fusion,
early store address, late reservation, detached ownership and publication-edge
forwarding. Subword forwarding remains off in these arms. `Prediction` adds
slot-1 training/selective taken deferral and retained redirect history. `Pair`
additionally enables correct-branch retirement pairing.

| Warm kernel | Macros | Baseline cycles | LSU | LSU + prediction | + pair |
| --- | ---: | ---: | ---: | ---: | ---: |
| hot loop | 336 | 252 | 252 | 252 | 252 |
| alternating, seed 1 | 132 | 135 | 135 | 138 | 139 |
| alternating, seed 17 | 132 | 234 | 234 | 138 | 139 |
| short backlog | 360 | 425 | 425 | 380 | 381 |
| copyback call/return | 672 | 1342 | 1174 | 1174 | 1174 |
| independent ALU | 396 | 267 | 267 | 267 | 267 |
| long alternating | 2112 | 2170 | 2170 | 2112 | 2112 |
| long backlog | 2520 | 2660 | 2660 | 2240 | 2241 |

Both seeds agree except where split above. Call/return gains **14.310% IPC**
(0.500745 to 0.572402), with head-incomplete cycles dropping 671 to 503. Long
backlog gains **18.750% IPC** (0.947368 to 1.125000); misses fall 29 to 1 of 224
retired branches. Long alternating gains **2.746%**, with 1/768 misses. The short
alternating seed-1 regression remains **2.174%**. These accuracy figures are not
representative Dhrystone coverage or proof of the overall 95% target.

Pairing again supplies no throughput gain: its call/return cycles remain 1174,
despite actually pairing branches. Head-incomplete cycles rise from 503 to 587
because the next incomplete head is exposed earlier; this is not an IPC loss.
Thus head-stall counts alone cannot rank retirement policies. All four arms have
zero cycles without room for a pair of ROB allocations. Mean occupancy in the
unpaired long backlog rises from 18.144 to 21.471 while IPC improves, illustrating
that higher occupancy is not itself a regression. Completed-prefix histograms
still expose a wider-retirement opportunity, but neither prove eligibility nor
predict a speedup. Actual wider/prepared-retirement experiments remain open.

Logs: `/tmp/composed-ipc-baseline.log`, `/tmp/composed-ipc-lsu.log`,
`/tmp/composed-ipc-predictor.log`, `/tmp/composed-ipc-paired.log`.
The required fast gate passes 384 tests, two ignored
(`/tmp/composed-ipc-fast.log`). The broad combined LSU/prediction oracle filter
also selected the long interrupt-storm sweep. It was deliberately interrupted
after 41 completed passing test cases, with no observed failure, while running
the long MMU-on/xbar storm (`/tmp/composed-ipc-oracle.log`). This is **not a suite
pass**. A bounded filter selecting the named lock-step IRQ/fault cases plus
branch, source-lifetime and SQ recovery checks passes **25 tests** in
`/tmp/composed-ipc-bounded-oracle.log`. The stopped run is retained as additional
partial evidence, not counted as another completed suite.
No board intervention was performed. This combined configuration has not yet
passed routed core or integrated-SoC timing; individual feature timing passes
must not be substituted for composition signoff.

## Four-wide retirement control: rename-side prerequisite — 2026-09-21

The composition profiles do not settle wider/prepared retirement: removing the
one-branch pairing restriction did not change throughput, but a four-wide ROB
was never exercised. Started that control experiment with an explicit
[retirement-bandwidth amendment](retirement-bandwidth.md).

`RenameStage(retireWidth = 4)` now sizes all five committed-map interfaces and
their one-cycle reclamation lanes to four while leaving speculative rename and
allocation two-wide. Default remains two. The existing RAT last-writer ordering
and free-ring compaction are reused; there is no new checkpoint or rollback
mechanism. The service exposes its actual lane count; the standalone test driver
uses that count rather than truncating it to two. The current ROB explicitly
rejects four lanes until all retirement consumers are integrated.

The source audit identifies not just maps/frees and SQ, but the shared **page-table
U/M queue interface**, committed CCR's same-edge precise-store bypass, system/FP
state, debug stop/restart/count and complete observation streams as consumers.
Normal D-side queue entries need every lane; I-side fetch entries retain their
ownerless/precommitted lifetime, as clarified below. A narrow in-order retirement-span service is a
candidate for queue authorization; it must identify exact retiring ROB IDs across
wrap and retain flush semantics. It is not implemented in this prerequisite.

The default full-core control reproduces all **14** warmed profile windows'
macro counts, cycle counts, branch denominators and misses exactly relative to
`/tmp/composed-ipc-baseline.log` (`/tmp/wide-rename-default-ipc.log`). This is the
expected zero-effect control, **not four-wide IPC**. The final expanded standalone
run passes **eight tests** (`/tmp/wide-rename-burst-correctness.log`): two hosted
rename tests cover all commit-lane masks, 32 two-lane and 128 four-lane batches,
dense WAW chains, distinct destinations, selective register-class writes,
discarded younger mappings and reset with pending frees. They also test two
consecutive maximum-width publications, with the four-wide FP pool completely
allocated before retirement begins. Every committed mapping and reclamation
record is checked. Six free-ring tests cover the three register-class shapes at
widths 2/4, including 15,000 seeded random cycles plus directed wrap, saturation,
flush and reset cases. The first fast gate passes **384 tests**, two ignored
(`/tmp/wide-rename-fast.log`); the final rerun also passes **384 tests**, two
ignored (`/tmp/wide-rename-final-fast.log`).
Do not queue synthesis for this plumbing; obtain full-core four-wide IPC first.

Next implementation boundary: expose ROB-owned retirement events via a service,
adapt queue authorization and all architectural/observation side effects, then
enable contiguous four-wide ordinary retirement with conservative cold boundaries.
Compare it against the same LSU/predictor baseline before implementing prepared
shadow-map publication. The latter remains an independent required experiment,
not something this prerequisite or prefix histograms have demonstrated.

## Retirement consumers: same-edge upper-lane notices — 2026-09-21

Added a ROB-owned `RobRetirementService` with four setup-allocated ID/valid
lanes. The unchanged two-wide ROB publishes its existing first two notices and
ties the upper pair idle. No new Global producer or delayed commit decision is
introduced. SQ and both shared U/M queue instances accept the upper pair through
the service; existing first-pair wiring is preserved during migration. Standalone
queues without the service retain the two-input specialization. The components
reuse equality matching, not a new wrap-range comparator or queue state.

Ownership clarification from the source audit: normal D-side U/M writes are
ROB-owned, but I-side fetch U writes and exception-episode D writes are already
`preCommitted`. They must retain that ownerless lifetime, not wait for an
arbitrary retired ROB ID. Updated the earlier checklist accordingly.

**56 tests pass** across SQ, split SQ, U/M queue and walker integration
(`/tmp/wide-queue-correctness.log`). New controls exercise one-to-four same-edge
retire notices followed immediately by flush, ROB wrap and all physical SQ head
positions, including unfilled younger reservations canceled by recovery. U/M
tests cover all four lanes, full queues, dead holes before committed survivors,
precommitted entries, held drain offers, reuse and reset. A real DTLB-walker test
publishes owned writes in lanes 2/3 and confirms both descriptor bytes update
after next-cycle flush. The default full-core profile reproduces all **14** prior
macro/cycle/branch/miss tuples exactly (`/tmp/wide-queue-default-ipc.log` against
`/tmp/wide-rename-default-ipc.log`). The required fast gate passes **384 tests**,
two ignored, with no failed or aborted suites (`/tmp/wide-queue-fast.log`). This is not
four-wide full-core IPC; ROB decision, architectural state folds and observations
remain the next integration boundary.

## Early store-address routed result — 2026-09-21

The pinned `12c23962` two-arm core-only gate is complete at 5 ns, three post-route
rounds (`/tmp/early-store-address-gate.sHxiPV`). Both arms enable the older load
fall-through and early-wake options; only the candidate enables early store
address. Baseline WNS **+0.017 ns**, candidate **−0.106 ns**, candidate TNS
−4.263 ns over 102 setup endpoints. Candidate hold **+0.016 ns** and pulse
**+1.958 ns**, with zero hold/pulse failing endpoints. LUTs 94,244 → 94,520,
FFs 38,751 → 38,804, BRAM tiles unchanged at 37. Candidate post-route WNS evolves
−0.214 / −0.118 / −0.114 / −0.106 ns. This is a timing miss, not 200 MHz signoff;
retain the already-measured IPC-positive candidate. The serial queue has advanced
to direct long MOVE load fusion, without stopping IPC work.

The worst path is ALU1 opcode state → arithmetic/flags → live
`ccrCompletion(1)` bypass → committed CCR, 18 logic levels, 5.086 ns data delay
(3.617 ns routing). Other failing families include decode expansion/immediates,
P3 size → P4 retry, fetch stall → I-cache prefetch enable, aligned-load admission
→ cache metadata, and P4 ROB ID → IQ dynamic-wait/cache write controls. This is
not evidence that all misses lie in the new store-address logic.

Concrete repair lead for the retirement integration: ALU `ccrObs` is already
aligned to its real completion, unlike the late precise-SQ case that motivated
the live flag bypass. Audit/prove which completion sources actually require
same-cycle retirement bypass before widening that fold. If only the precise LS
source can arrive late, retaining its bypass while removing redundant ALU paths
would shorten this cone without adding a recurrence cycle. This is a hypothesis,
not an implemented repair or permission to remove the precise-store safeguard.
The other failing cones still require their own timing evidence.

## Four-wide retirement full-core control — 2026-09-21

Implemented the actual optional four-wide ROB, not just completed-prefix
profiling. Rename/dispatch allocation stays two-wide. The ROB consumes a safe
contiguous prefix and publishes all map/free, SQ/U/M, CCR, system-capture,
FP-ever-executed and PC updates. Same-edge precise-store CCR bypass is retained.
Branches remain on the one training port; system/privileged/RTE upper entries,
armed debug stops and trace retain conservative behavior. A marked breakpoint
now also blocks lane 1 (previously that guard existed only at the head), as well
as the new upper lanes. Directed tests also exposed a manual halt retiring one
macro too far when a cracked macro ended in lane 1: normal boundary detection
previously checked only lane 0. It now includes both conservative stop lanes and
uses the completed macro's restart PC. Macro histories compact into two/four banks; counters,
VIO and ordinary commit observations include all lanes. Exception observation
channel 2 is preserved for existing exception-only observers. The old detailed
two-lane ILA format explicitly rejects four-wide elaboration.

Controls: `IPC_RETIRE_WIDTH=2/4` for the benchmark harness,
`LOCKSTEP_RETIRE_WIDTH=4` for the oracle harness, and `--retire-four` for core
generation. All default to two. These are experimental options, not a board
configuration change. No new Global producer or rollback mechanism is added.

Matched warmed windows, seeds 1/17, L2 hit 5 cycles / DDR 70 cycles. `Composed`
is the seven LSU options plus selective taken-slot-1 deferral/training and retained
redirect history, with subword forwarding and correct-branch pairing disabled.
Each run has an instrumentation-disabled control with identical macro/cycle
counts. All 14 new default two-wide macro/cycle/branch/miss tuples exactly match
the earlier queue-prerequisite baseline.

| Kernel | Macros | Original 2-wide | Original 4-wide | Composed 2-wide | Composed 4-wide |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hot loop | 336 | 252 | 252 | 252 | 252 |
| Short alternating, seeds 1 / 17 | 132 | 135 / 234 | 135 / 234 | 138 | 138 |
| Short backlog | 360 | 425 | 415 | 380 | 376 |
| Copyback call/return | 672 | 1,342 | 1,341 | 1,174 | 1,173 |
| Independent ALU | 396 | 267 | 267 | 267 | 267 |
| Long alternating | 2,112 | 2,170 | 2,170 | 2,112 | 2,112 |
| Long backlog, seeds 1 / 17 | 2,520 | 2,660 | 3,204 / 3,360 | 2,240 | 2,240 |

The original long-backlog misses rise from 29/224 to 74/224 and 90/224. This is
an observed interaction with prediction/recovery timing, not proof of the exact
predictor root cause. With the improved predictor, both widths retain one miss
out of 224 and the same throughput. The four-wide composed long window exercises
112 three-entry and **336 four-entry** retirement cycles: this is not an unexercised
feature. The short composed window gains **1.064% IPC**; call/return's one-cycle
gain is just **0.085%** and may include window-boundary quantization. There is no
general throughput improvement demonstrated here, despite real catch-up bursts.
In the composed long-backlog window, mean ROB occupancy falls 21.471→19.038,
but head-incomplete cycles rise 672→1,008 while IPC is identical. Faster draining
exposes the next blocked head earlier; the stalled-head counter alone is not a
throughput score. Neither width exhausts the ROB's two-allocation capacity in
these measured windows.

Logs: `/tmp/wide-rob-base-and-directed.log`, `/tmp/wide-rob-four-ipc.log`,
`/tmp/wide-rob-composed-2-ipc.log`, `/tmp/wide-rob-composed-4-ipc.log`. All four
profile suites pass. Correctness results are recorded below. No synthesis is
queued for this low-gain control; the existing
LSU queue continues independently. No routed four-wide, integrated SoC or board
result is claimed. Prepared shadow-map publication remains a separate required
experiment, not disproven by this control, but it must show a benefit that simpler
catch-up bandwidth did not deliver in these windows.

The four-wide composed correctness run passes **30** bounded integer/memory/
exception oracle tests and **28** FP oracle tests, including IRQ entry/RTE,
nonresident/write-protected mappings, physical aliases, SQ generation/flush and
source lifetime, precise-store same-edge CCR folding, CHK fault flags and control
register captures. Three new directed ROB tests cover all seven barrier kinds at
every lane, breakpoint stop PCs, manual/halt-after stops at every macro boundary
at both widths, 20 full bursts across ROB wrap, FP/FPCC notices, macro counters
and youngest/restart PCs. The history suite passes four tests, including every
four-lane sparse macro mask, wrap and exact instruction-count readback.
`/tmp/wide-rob-correctness.log` records the ROB/oracle runs and successful
four-wide production RTL generation; `/tmp/wide-rob-base-and-directed.log`
records the history tests. RTL generation is not synthesis or timing closure.
The required `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` gate passes
**385 tests**, two ignored, with no failed or aborted suites
(`/tmp/wide-rob-fast.log`).
After the debug-boundary fix, final default two-wide and composed four-wide
profile reruns reproduce all 14 respective macro/cycle/branch/miss tuples
exactly; the four-wide retirement histograms also match
(`/tmp/wide-rob-final-default-ipc.log`, `/tmp/wide-rob-final-composed-ipc.log`).

## Prepared shadow-map retirement — 2026-09-21

Actual default-off prototype, not a renamed wider same-cycle RAT write path.
Two preparation lanes fold five architectural maps into one unpublished image;
publication swaps the complete image while preserving every commit/free/queue/
CCR/PC/debug event. Caps 4/8/16 are explicit alternatives, production remains
two-wide. [Contract and limitations](prepared-retirement.md).

The first cap-8 attempt canceled on every ordinary retirement, produced **zero**
batch publications and reproduced all fourteen original two-wide cycle counts
(`/tmp/prepared-retire-8-ipc.log`). That is a failed mechanism exercise, not an
IPC validation of batching. The revised implementation freezes the endpoint but
retains its shadow image across ordinary consumption of the prefix: those older
updates have simply become architectural. It never withholds ordinary retirement
to complete preparation. Flush/debug cancellation invalidates the image.

The initial cap-16 freelist could not elaborate: sixteen return ports plus the
initialization write exceeded the XOR multiwrite lowering's read-port limit.
The experimental cap-16 ring now uses sixteen conflict-free low-address banks,
multiplexing initialization onto each bank's one write port. Small single-entry
banks are registers. Pointer recovery and one-cycle free visibility are unchanged.
This is **not** a BRAM/area claim or the proposed narrow reclamation FIFO.

Composed LSU/predictor, seeds 1/17, L2 hit 5 / DDR 70, matched warmed macro windows:

| Kernel | Macros | Ordinary 2 | Ordinary 4 | Prepared 4 | Prepared 8 | Prepared 16 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Hot loop | 336 | 252 | 252 | 252 | 252 | 252 |
| Short alternating | 132 | 138 | 138 | 138 | 138 | 138 |
| Short backlog | 360 | 380 | 376 | 380 | 375 | 376 |
| Copyback call/return | 672 | 1,174 | 1,173 | 1,174 | 1,174 | 1,174 |
| Independent ALU | 396 | 267 | 267 | 267 | 267 | 267 |
| Long alternating | 2,112 | 2,112 | 2,112 | 2,112 | 2,112 | 2,112 |
| Long backlog | 2,520 | 2,240 | 2,240 | 2,240 | 2,240 | 2,240 |
| Divider backlog (synthetic pressure) | 1,296 | 3,304 | 3,298 | 3,303 | 3,301 | 3,297 |

Both seeds agree in these composed windows. Long-backlog batch histograms are
56 three-entry publications at cap 4, 112 six-entry publications at cap 8, and
112 thirteen-entry publications at cap 16. The mechanism is genuinely exercised,
but larger batches do not improve that window's throughput. Cap 8's short-backlog
gain is **1.333% IPC**, 0.947368→0.960000, with unchanged branch misses. Do not
extrapolate it to sustained code or board performance.

The divider control deliberately creates a long head stall followed by 24 ADDA
instructions; its warmed window has 2,633 cycles without two-entry ROB capacity.
Every retirement alternative retains exactly that count. Ordinary four-wide
retires four on 288 cycles; prepared caps 4/8/16 each publish 48 full-cap batches.
The measured whole-window gains shorten the final drain rather than the recurring
divide bottleneck: the original and composed two-wide controls and measured
cap-16 arms all retire the 48 divisions at exactly **70-cycle intervals** (47
intervals per window, both seeds). The best seven-cycle difference is therefore
not a steady-state speedup. Final cadence evidence is in
`/tmp/prepared-retirement-final-{default,cadence}.log` and
`/tmp/retirement-matrix-prepared16-pressure.log`.
Preparation starts/publications can straddle a warmed-window boundary, so 47
starts with 48 publications is expected and does not imply a duplicated batch.

The original predictor configuration exposes the same timing-sensitive regression
seen in the four-wide control. Short backlog takes 425/417/415 cycles at prepared
caps 4/8/16 versus 425 two-wide; the original long backlog takes 2,660/2,966/
3,364 cycles at seed 1 and 2,660/2,966/3,173 at seed 17. Long-window misses are
29/57/89 (seed 1) and 29/57/73 (seed 17), all out of 224 branches, versus 29
two-wide. The other ten original kernel/seed pairs outside the backlog kernels
retain their earlier cycle and miss counts. These are observed prediction/recovery
interactions, not proof of the exact predictor cause. Do not hide them behind the
composed configuration's cleaner result. Original-arm evidence also includes
`/tmp/retirement-matrix-prepared{4,16}-original.log`.

Profile evidence: `/tmp/prepared-retire-8-{prefix,composed}-ipc.log`,
`/tmp/prepared-retire-16-composed-ipc.log`,
`/tmp/prepared-retire-pressure-baseline.log`, and
`/tmp/retirement-matrix-{four-composed,prepared4-composed,prepared8-pressure,prepared16-pressure}.log`.
Every measured arm has a matching instrumentation-off control; the log field
`baselineCycles` denotes that same-configuration control, **not** the two-wide
comparison in the table. All macros/register checks and retired-branch denominator
checks must pass. Wider retirement is in micro-ops; IPC always counts macros.

Priority: preserve the prototype and controls, but do not add more reclamation/
checkpoint machinery on the strength of these small or absent gains. Investigate
the actual recurring dependency and prediction losses next. Prepared caps are
not routed, integrated into the SoC, or verified on the board.

The first cap-16 oracle gate passed 31 cases but exposed two fixture assumptions
(`/tmp/prepared-retirement-final-correctness.log`). Four NOPs did not separate two
flag controls: both retired in one fourteen-entry batch, correctly leaving the
younger N flag rather than the intermediate Z flag. Padding by a maximum batch
width restores the intended test of the real committed CCR on the observation
edge. The ROM-pop IRQ fixture already triggers on **raw** retire signals, but a
single batch can legally pass more than its assumed one extra macro boundary.
Its wide-mode recheck derives acceptance from the last emitted architectural
macro at interrupt entry, not the DUT's stacked PC, bounds it to the remaining
pop-chain boundaries, requires exactly one raised/accepted IRQ, and asks Musashi
for that event schedule. PC/SR/CCR/register/A7 and memory comparisons remain
strict, and the longer window includes handler and RTE even after all pops.
Requested/accepted PCs are logged; this mode is **not** exact-boundary coverage.
The ordinary two/four-wide fixture retains its original scheduling and retry.
Both corrected cases pass, including all fifteen cache/timing/postincrement IRQ
combinations (`/tmp/prepared-retirement-fixture-recheck.log`). No CPU semantics
were changed to make those fixture assumptions hold.

Correctness: three directed ROB tests pass, covering caps 4/8/16 with ordinary
prefix consumption and repeated ROB wrap, seven barrier types across upper
positions, flush cancellation, resource-pressure partial sealing, macro endpoints
and a later debug stop. Twenty-three freelist/map/history tests pass, including
2,500 fixed-seed allocator steps per class/width, one-cycle reclamation across
flush/reset, sparse/wide WAW frees, all five map classes, ordinary prefix commits
before image publication, canceled images and 4/8/16-bank PC history readback.
The cap-16 composed oracle run passes 33 integer/memory/exception tests across
the initial run and two fixture rechecks, plus all 28 FP tests (two existing
ignored cases). Cap-16 production RTL generation passes; this is **not synthesis**.
Logs: `/tmp/prepared-retirement-final-correctness.log` and
`/tmp/prepared-retirement-fixture-recheck.log`.

The final default-mode profile rerun reproduces all fourteen previous
macro/cycle/branch/miss tuples exactly; the new divider control adds two checked
rows. Both corrected fixtures also pass in unchanged two-wide mode
(`/tmp/prepared-retirement-final-default.log`). No board action was taken.
The required `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` gate passes
**387 tests**, two ignored, zero failed or aborted suites
(`/tmp/prepared-retirement-final-fast.log`).

Reproduction switches: `IPC_PREPARED_RETIRE=4|8|16`,
`LOCKSTEP_PREPARED_RETIRE=4|8|16`, or `GenFullCoreSynthVerilog
--prepared-retire-4|--prepared-retire-8|--prepared-retire-16`. Select only one
retirement experiment; defaults remain ordinary two-wide.

## Late store-data capture versus load issue — 2026-09-21

Measured the unconditional `!lateDataCapture` issue interlock. Store-data capture
uses `rdData`, whereas a plain load uses independent base/index ports. The trial
allowed LOADs without source-B, auto-update, stack/CCR restore, alternate-space,
supervisor, LEA or alias-store behavior to issue concurrently. It added no state
or read port and did not change SQ ordering, translation or completion priority.
The existing source-ready assertions remained, with a qualified issue-exclusion
assertion and new assertions checking actual `rdData` ownership.

Both arms enable direct long MOVE loads, early store address, SQ reservation,
detached ownership and publication-edge forwarding. The corpus is the same
17 kernels × two seeds (1/17) × four fall-through/early-wakeup combinations,
with `l2:5:70`, first-to-last retired-macro windows and ordinary two-wide retirement.
The telemetry-only baseline exactly reproduces all **136** macro/cycle tuples
from `/tmp/sq-publish-candidate-ipc-v2.log`.

**Result: 136 unchanged, zero improvements, zero regressions.** All macro counts
match. In the load-fed store recurrence with early integer wakeup, the baseline
has 55 eligible blocked-load edges per window. The candidate performs two actual
capture/load overlaps, but remains **286 / 285 cycles for 130 macros** at seeds
1 / 17, with or without aligned fall-through. Both retain 64 captures,
reservations and publications, 61 publication-forward hits, and 60 owner-wait
cycles coinciding with P4 occupancy. All other windows have no eligible overlaps.
There are eight actual overlaps across the entire candidate corpus. An upstream
issue opportunity does not translate into a saved end-to-end cycle here.

Evidence: `/tmp/capture-overlap-{baseline,candidate}-ipc.log`; the scoped trial
diff is `/tmp/capture-overlap-evaluated.patch`. Bench architectural end-value,
address-order, reservation/publication and RTL assertion checks pass in both
arms. This was an IPC screening experiment, **not** a completed full oracle or
physical timing qualification. A drafted additional oracle test was withdrawn
without being run when the candidate showed no IPC benefit; no coverage from
that draft is claimed.

Disposition: remove the functional option and its plumbing, preserving only
simulation opportunity counters and data-port ownership assertions. No synthesis
job is warranted for this zero-gain variant. Next attribute P4 forwarding/retry
and completion-port conflicts: the retry path registers a fresh SQ answer before
consuming it, while a detached store's completion has priority over a forwarded
load. Removing that delay or announcing readiness earlier needs guaranteed
completion availability, not merely SQ space. These are follow-up hypotheses,
not measured improvements or permission to weaken precise exception ordering.

The final restored-interlock rerun (`/tmp/capture-overlap-final-ipc.log`) passes
and exactly reproduces all 136 baseline macro/cycle tuples and all three capture
telemetry fields. A separate RTL audit identified proposal 17 in the alternatives
ledger: selected-completion NZVC wakeup still lags the optional integer wakeup by
one cycle. It is not implemented or measured in this experiment.
The required `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` gate passes
**387 tests**, two ignored, zero failed or aborted suites
(`/tmp/capture-overlap-final-fast.log`). No board operation was performed.

## Selected-completion NZVC wakeup — 2026-09-21

Default-off `earlyNzvcWakeup` reuses the existing LSU→IQ NZVC readiness port to
announce an already-selected successful completion one cycle before its normal
registered flag writeback. It is independent of integer early wakeup. Front,
back, detached-store and precise-replay winners supply the destination; faults
and orphan replays do not announce. No flag data, retirement, SQ authorization,
X/FPCC timing, queue or PRF port changes. The cycle-by-cycle assertion requires
exact next-cycle valid/write destination agreement. Contract:
[resident-load latency](ls-hit-latency.md#guaranteed-next-cycle-nzvc-wakeup-candidate).

`LsNzvcWakeIpcSpec` runs six controls, seeds 1/17, `IPC_MEM=l2:5:70`, with all
retained LSU options plus selective slot-1 prediction and retained history in
both arms. Retirement remains ordinary two-wide. Recurrences execute 96
iterations and exclude the first 16; branch controls execute 64 and exclude 16.
IPC counts architectural macros, not uops. All end-value and branch-denominator
checks pass in both arms (`/tmp/ls-nzvc-targeted-ipc-v2.log`):

| Control | Measured macros | Before cycles (1 / 17) | Early NZVC cycles | IPC change |
| --- | ---: | ---: | ---: | ---: |
| copyback store → Scc | 160 | 636 / 636 | 556 / 556 | +14.39% |
| copyback load → Scc → store | 240 | 799 / 799 | 719 / 719 | +11.13% |
| precise store → Scc | 160 | 1731 / 1713 | 1652 / 1646 | +4.78% / +4.07% |
| precise load → Scc → store | 240 | 1336 / 1339 | 1334 / 1339 | +0.15% / unchanged |
| independent load → branch, zero/nonzero | 240 | 192 / 192 | 192 / 192 | unchanged |

The copyback recurrences save exactly one cycle per measured iteration. Both
load/branch controls retain one miss out of 96 retired branches; there is no
measured accuracy improvement. Seven of twelve matched control/seed pairs
improve, five are unchanged, none regress. These isolate a flag-dependency
latency reduction, **not representative Dhrystone or board performance**.

The initial run exposed a benchmark-observer defect, not a reason to change CPU
semantics: it replaced every branch-EU writeback with a no-op, losing Scc/DBcc and
RTS/RTR integer writes. `CoreBenchHarness` now records the existing `BrWbObs`
integer write, value, destination and keep-commit marker, matching the oracle
harness. The strict final-register check remains; without the correction the
load/Scc recurrence reports its penultimate load value instead of the Scc result.
Initial failure evidence: `/tmp/ls-nzvc-targeted-ipc.log`.

The new strict flag/partial-register oracle test passes in both precise and
copyback modes, disabled and enabled. Its first disabled copyback run checked
backing RAM before eight cold eviction stores had drained at DDR=70: the fixed
200-cycle harness delay was insufficient. An architecturally ordered inhibited
write/read now completes after the eviction sequence, retaining the exact memory
comparison rather than increasing a timing constant. Evidence:
`/tmp/ls-nzvc-oracle-baseline{,-v2}.log`. The enabled broader run passes **49
tests** (`/tmp/ls-nzvc-oracle-candidate.log`), including real late-store capture,
full SQ, physical aliases, splits, retained completion, redirect cancellation,
nonresident/write-protected page recovery, RTR, CCR/IRQ storms and the ROM A7 pop
chain. The device-read IRQ stress observes 150 actual reads for 150 retired
loads. All next-cycle readiness/tag assertions remain active.

The initial broad 136-window LSU candidate run passes: **12 improve, 124 are
unchanged, none regress** versus the previous publication-forward baseline.
All macro counts match. With early integer wakeup, divide→store→load is
2,367→2,336 cycles for 98 macros at both seeds and either fall-through setting
(+1.33% IPC). All eight delayed-store/disjoint-load recurrence windows save one
total cycle; the other 124 windows are unchanged. This also has a concrete flag
dependency: `OperationDecoder` sets `readsNzvc` for DIV.W, and `DivEuPlugin`
captures old NZVC for overflow preservation. The following divide therefore
waits on the load's flags even though this kernel's divides do not overflow.
Evidence: `/tmp/ls-nzvc-corpus-candidate.log`. The fresh disabled rerun
(`/tmp/ls-nzvc-corpus-disabled.log`) passes and exactly reproduces all 136
previous macro/cycle tuples, including the observer correction. Branch profiles
separately check composition with prediction: all **16 warmed profile windows**
retain identical macro counts, cycles, branch counts and misses with the option
off/on, and each profile matches its instrumentation-off control. The set includes
long alternating branches, long retirement backlog, calls and divider backlog;
no new gain is claimed there. Evidence: `/tmp/ls-nzvc-profile-{0,1}.log`.

Ten additional overflow/DIVREM oracle tests pass, including old N/Z/C preservation
and misprediction recovery. The full composed production RTL generator accepts
`--early-ls-nzvc-wakeup` and completes successfully; this is elaboration, not a
physical timing result (`/tmp/ls-nzvc-overflow-production.log`). Together with
the earlier run, 59 selected oracle tests pass for the enabled candidate.

Reproduction switches: `IPC_LS_EARLY_NZVC=1`, `LOCKSTEP_LS_EARLY_NZVC=1`, or
`GenFullCoreSynthVerilog --early-ls-nzvc-wakeup`. Defaults remain off. Broad IPC,
profile, fast-gate and routing results must be recorded separately; targeted
recurrence gains alone do not qualify the candidate for the board.

The required `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` gate passes
**387 tests**, two ignored, zero failed or aborted suites
(`/tmp/ls-nzvc-final-fast.log`). The candidate remains disabled by default.
Matched routing is queued as `m68k-ls-nzvc-gate.service`, artifacts
`/tmp/ls-nzvc-gate.cM6rEn`: both arms use the full retained LSU combination,
selective slot-1 deferral/training and retained history, with ordinary two-wide
retirement; only the candidate enables early NZVC wakeup. It uses the shared
Vivado mutex, the existing 5 ns / three-post-route-round recipe, and the existing
build-log window. No routed result, whole-SoC closure or board improvement is
claimed. No board operation was performed during this experiment.

### Timing-runner recovery during this experiment

The detached-owner baseline completed at +0.050 ns, reproducing the prior
reservation result, but its systemd wrapper then exited 127 because `rg` was not
on the daemon's PATH. The native route was complete; it was not restarted.
`m68k-sq-detach-resume-876e58f5.service` verifies the pinned baseline commit,
netlist checksum and terminal signoff, extracts its summary with an absolute
system-tool path, and builds only the missing detached candidate under the same
Vivado mutex. The already-running publication baseline was left untouched;
`m68k-sq-publish-resume-3f53a39b.service` waits for that original wrapper to end
and performs the same guarded recovery only if its exit code is 127. Both reuse
the existing artifact directories and the existing `build-logs:0` log window.

## Queued detached-store contexts — 2026-09-21

Baseline: `98565c72`, including early LSU NZVC wakeup. The audit found that a
second translated store waiting on data holds P3 solely because the detached
completion owner is occupied. A bounded FIFO now optionally queues additional
source/completion contexts. Address issue and SQ allocation remain ordered;
addresses, byte masks and data are not duplicated. One existing readiness lookup
and one PRF read port process contexts oldest first. Context replacement clears
readiness qualification and held NZVC; flush/reset clear the entire owner queue.
The default remains one owner. See [the contract](ls-hit-latency.md).

The four-owner generated RTL contains a three-entry, 23-bit synchronous-read
tail memory plus FIFO control/read registers; this is **not** a measured BRAM or
area claim. Two owners use the one-entry FIFO specialization. Admission does not
borrow a completion-edge credit. Empty-queue admission still directly installs
the active context, preserving the old first-store latency.

### Targeted matched IPC

`LsQueuedStoreIpcSpec` compares one, two and four owners with all retained LSU
options, selective slot-1 prediction and retained history, ordinary two-wide
retirement, `IPC_MEM=l2:5:70`, seeds 1/17. Each unrolled recurrence runs 48
iterations with eight warm-up iterations excluded. A divide feeds 1/2/4/8 stores,
followed by a disjoint or aliasing load feeding the next divide. Both seeds give
the same counts below; all final D0 values are checked.

| Stores / following load | Measured macros | One owner cycles | Two owners | Four owners |
|---|---:|---:|---:|---:|
| 1 / disjoint | 120 | 2617 | 2617 | 2617 |
| 1 / alias | 120 | 2852 | 2852 | 2852 |
| 2 / disjoint | 160 | 3012 | 2619 | 2619 |
| 2 / alias | 160 | 2972 | 2853 | 2853 |
| 4 / disjoint | 240 | 3252 | 3053 | 2623 |
| 4 / alias | 240 | 3212 | 3013 | 2857 |
| 8 / disjoint | 400 | 3612 | 3373 | 3255 |
| 8 / alias | 400 | 3652 | 3373 | 3255 |

All **48 runs pass**. Each larger capacity improves 12 matched windows, leaves
four unchanged and regresses none. The two-store disjoint recurrence improves
15.01% IPC; the four-store disjoint recurrence improves 23.98% with four owners.
These gains include actual queued admissions and younger-load overtakes, not
merely reduced owner occupancy. Aliasing controls have zero load overtakes;
they benefit from faster store completion, not illegal alias bypass. The
eight-store runs fill the eight-entry SQ and do not gain actual disjoint-load
overtakes at these context capacities. No representative workload, board or
routed timing speedup is inferred from these targeted kernels.
Evidence: `/tmp/ls-queued-targeted-ipc.log`.

New strict-oracle controls cover different divide-produced source registers,
overlapping byte/word/long stores and repeated context/ROB reuse, plus redirects
canceling multiple wrong-path store reservations. Their one-owner baseline
passes both tests (`/tmp/ls-queued-oracle-baseline.log`). Candidate and broader
regression results are recorded below when complete.

### IRQ fixture findings and incomplete broad run

The initial broad four-owner selection included every test containing `IRQ`,
including long legacy stack-boundary matrices. Before stopping that run, **58
tests passed and three odd-SSP sweeps failed**, at boot offsets 0/2/4. Completed
checks included mixed-source queue use (120 queued admissions / 144 captures),
16 multi-owner redirect cancellations, aliases/splits, full SQ, retained-NZVC
completion contention, page/write-protection faults, device-read exact-once,
long interrupt storms, CCR/RTE and A7 byte-stack checks. Some storm tests use
architectural invariants rather than Musashi. This is **not a full-suite pass**.
Evidence: `/tmp/ls-queued-oracle-4.log`.

An exact matched one/four-owner reproduction fails at the same first odd-SSP
boundary (`/tmp/ls-queued-odd-ssp-{1,4}.log`). The DUT accepts the interrupt on
the **second** visit to a shared RTS PC; the fixture's PC-only oracle event takes
the interrupt on its first visit. The existing +/-2 program-boundary range
already contains that second visit, but its occurrence identity was discarded.
The fixture now retains program positions and arms a repeated-PC oracle event
with an existing zero-level IRQ event at a first-visited intervening PC. This
changes no DUT logic, introduces no oracle-engine change and does not widen the
permitted window or relax register/CCR/frame/local-memory comparison. The same
qualified event sequence is used for the trace and final memory oracle.

Both capacities now match the original boundary 20, including exact frame and
local-memory checks. Continuing the whole boot-0 sweep exposes a **further shared
baseline failure at boundary 33**: IRQ acceptance occurs beyond the fixture's
two-instruction assumption. It remains failing and unresolved; do not claim the
whole sweep passed or silently enlarge its window. Evidence:
`/tmp/ls-queued-odd-ssp-fixed-{1,4}.log`. A bounded regression separately exercises
the repeated-RTS boundary at boot offsets 0/2/4, without excluding or marking the
original whole-sweep test ignored. Tracing the remaining IRQ acceptance schedule
and completing full regression are outstanding acceptance work. Reproduction in
both capacities alone does not establish that the delayed acceptance is legal.

The source-reuse test was also strengthened to change each iteration's producer
values, so repeated constants cannot hide stale data after slot reuse. Its v2
checks and the repeated-RTS regression are rerun at all three capacities. The
first concurrent fast-gate attempt was stopped on discovering that `test-fast`
contains untagged Verilator tests; it is not counted as a pass. The final run is
serialized with the other simulator jobs.

The strengthened bounded set passes **13 tests at each of one/two/four owners**
(`/tmp/ls-queued-oracle-v2-{1,2,4}.log`): changing-value source reuse, redirect
cancellation, the repeated-RTS regression at three boot offsets, overflow/DIVREM
and instruction-length overflow controls. Two/four owners each observe 120 queued
admissions, 144 publications and 16 multi-owner cancellations in the new controls.
These results do not erase the separate whole-sweep boundary-33 failure.

The first four-owner broad corpus completes all 136 windows: **4 improve, 128
unchanged, 4 regress**, with identical macro counts. Without early integer wakeup,
the load-fed store recurrence saves four total cycles (348/347→344/343, +1.16%).
With it, the same recurrence loses one cycle (286/285→287/286, −0.35%), with either
fall-through setting. The other 128 windows are unchanged. This is a small
finite-window regression, not evidence of a sustained loss; likewise the four
saved cycles are not a general throughput claim. Evidence:
`/tmp/ls-queued-corpus-4.log`. The fresh one-owner control completes all 136
windows and exactly reproduces the prior NZVC baseline's macro/cycle tuples
(`/tmp/ls-queued-corpus-1.log` versus `/tmp/ls-nzvc-corpus-candidate.log`).

The two-owner broad run also completes 136 windows: **4 improve, 132 unchanged,
none regress** (`/tmp/ls-queued-corpus-2.log`). It keeps the four-cycle improvement
without early integer wakeup and exactly matches the old early-wakeup recurrence.
Its simpler single-entry tail is therefore the initial implementation front-runner,
while four owners retain a larger gain on the divide/four-store target. Neither
is enabled by default or claimed as a representative system speedup.

The final four-owner focused selection passes **37 tests**, covering queued/late
stores, forwarding and alias controls, precise I/O, page/write-protection faults,
direct long MOVE, NZVC, RTR and CCR/RTE behavior
(`/tmp/ls-queued-oracle-focused-4.log`). The one/four-owner branch/retirement
profiles match all **16 windows** exactly in macro counts, cycles, retired
branches and misses (`/tmp/ls-queued-profile-{1,4}.log`). Production four-owner
RTL generation passes (`/tmp/ls-queued-production.log`); this is elaboration,
not synthesis or timing closure. The serialized mandatory default-feature gate
(`make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast`) passes **387 tests, two
ignored, zero failures** (`/tmp/ls-queued-final-fast.log`).

A follow-up targeted test adds resident-load producers and pointer-dependent
next loads, with one/two/four stores and disjoint/aliasing controls. Both seeds
give the same results; final D0 and A0 values are checked:

| Load-fed stores / following load | Measured macros | One owner cycles | Two owners | Four owners |
|---|---:|---:|---:|---:|
| 1 / disjoint | 120 | 432 | 432 | 432 |
| 1 / alias | 120 | 472 | 472 | 472 |
| 2 / disjoint | 160 | 593 | 473 | 473 |
| 2 / alias | 160 | 592 | 473 | 473 |
| 4 / disjoint | 240 | 833 | 634 | 607 |
| 4 / alias | 240 | 832 | 633 | 595 |

All **84 expanded target runs pass**, including the original 48 divide-fed runs.
Each larger capacity now has **20 improved, eight unchanged, zero regressed**
matched windows. Two owners improve the load/two-store disjoint recurrence by
25.37% IPC and load/four-store disjoint recurrence by 31.39%; four owners improve
the latter by 37.23% and its aliasing counterpart by 39.83%. These load-fed cases
have **zero measured younger-load overtakes** at every capacity: the gain is
store handling, not demonstrated load bypass. Queued admissions are observed for
two/four-store cases, while single-store controls never queue and remain unchanged.
Evidence: `/tmp/ls-queued-targeted-ipc-v2.log`. Two owners remain the simpler
front-runner; four owners are a measured burst-throughput tradeoff, retaining the
separate one-cycle broad-corpus regressions. Neither is a board speedup claim.

A concrete **unimplemented** queue repair lead is empty-tail
head turnover: when context capacity is already reserved, an incoming context
could directly replace a completing active owner instead of making a synchronous
FIFO round trip. Admission must still use registered capacity, not completion-edge
credit, and every replacement must clear readiness qualification. Compare cycles
before adopting it; do not hide the current regression behind that proposal.

### Background timing queue

The publication-forward comparison's baseline at pinned `3f53a39b` completed
at **−0.073 ns** setup slack (`/tmp/sq-publish-gate.GHXuam/baseline-summary.txt`).
Its wrapper then hit the previously identified missing-`rg` exit 127; the guarded
recovery reused that completed baseline and generated only the missing candidate.
The detached-store candidate and publication candidate remain serial under the
shared Vivado mutex, followed by the matched early-NZVC comparison. This baseline
is not the new queued-context candidate and not integrated SoC timing.

That baseline has 137 setup-failing endpoints (TNS −5.023 ns), hold +0.028 ns,
pulse-width +1.958 ns, 94,769 LUTs, 38,759 FFs and 37 BRAM tiles. Its worst path
is fetched slot-1 opcode bit 3 to packed-uop immediate bit 30, 15 logic levels,
68.25% routing. The generated net names connect it to static bit-field EA
displacement/PC-relative address, byte offset and spill-byte `+4` arithmetic.
A concrete **unimplemented** repair lead is factoring the small constant/offset
sums before the wide address addition, or moving cold EA preparation across an
existing cut without adding a hot-path stage. Verify the exact bit-field/PC-relative
semantics and matched IPC rather than assuming reassociation changes mapping.
The next path is SQ ROB-ID through forwarding/control to D-cache valid-RAM address
at −0.072 ns (21 levels, 67.37% routing); fixing only decode may expose that path.
Reports: the baseline's `synth/fullcore_route_{timing,util}.rpt`.

The older single-detached-owner comparison subsequently completed at pinned
`876e58f5`: baseline **+0.050 ns**, detached **−0.102 ns**, TNS −12.277 ns / 347
setup-failing endpoints, hold +0.026 ns, pulse-width +1.958 ns. LUTs
94,078→94,417; FFs 38,779→38,749; BRAM tiles remain 37. Its worst path is
`RobPlugin_logic_head_reg[1]` to `FpuControlPlugin_logic_uiSrcOperand_reg[30]`,
16 levels and 70.71% routing. Preserve the measured IPC-positive candidate;
an explicit cold FPU exception-operand capture phase is a repair lead, subject
to proving that source tags/values remain owned until capture and recovery.
No hot-path bubble should be added merely to repair this exceptional path.
Artifacts: `/tmp/sq-detach-gate.97VroC/{baseline,detached}-summary.txt` and
the detached arm's `synth/fullcore_route_{timing,util}.rpt`. The recovery unit
finished successfully; publication-forwarding now holds the Vivado mutex.

The queued-context implementation is committed as `b4c99f82`. A matched
one/two/four-owner core-only physical screening is queued behind early NZVC:
`m68k-ls-queued-gate.service`, `/tmp/ls-queued-gate.xtEogz`, pinned to
`b4c99f8261052a0d067a82c6123de12d2e213c1b`. Every arm uses the composed LSU and
selective-predictor/history options with ordinary two-wide retirement; only owner
capacity differs. The script uses fresh detached worktrees, netlist SHA-256s and
the shared Vivado mutex. The existing `build-logs` tmux pane follows these logs.
This is queued screening, not a timing result or functional acceptance; the
shared IRQ boundary-33 issue remains open. No board reset, halt or reload occurred.

## Empty-tail detached-context turnover — 2026-09-21

Baseline: `b4c99f82` (queued contexts), documentation-only HEAD `573a303a`.
An already-admissible new context can directly replace a completing active owner
when the FIFO tail is empty. Readiness still depends on actual tail capacity,
not same-cycle completion credit. If the old owner loses completion arbitration,
the new context enters the tail normally. Tail emptiness selects the context
payload; completion affects only the narrow handoff enable. Every replacement
clears readiness qualification. One-owner RTL is unchanged.

The first matched four-owner broad corpus passes all **136 windows**. Against
the original four-owner version, **four improve, 132 are identical, none regress**.
The four early-integer-wakeup short-store/load recurrence cases recover their
one lost cycle (287/286 to 286/285 with either fall-through mode), without changing
macro counts. Actual tail admissions fall from 60 to two in these windows; most
handoffs now avoid the tail, but this is only a one-cycle finite-window IPC gain,
not a steady-state throughput claim. The no-early-wakeup cases stay at 344/343.
Evidence: `/tmp/ls-turnover-corpus-4.log` versus `/tmp/ls-queued-corpus-4.log`.
The expanded **84-run target passes**. Compared with the committed queued-context
version, 82 windows are identical and two improve: four-owner load/four-store
disjoint recurrence is **607→598 cycles** at both seeds (+1.505% IPC), keeping
240 measured macros and zero overtakes. This is 39.30% higher IPC than the original
one-owner 833-cycle target. Two-owner target windows and all one-owner controls
are unchanged. Evidence: `/tmp/ls-turnover-targeted.log` versus
`/tmp/ls-queued-targeted-ipc-v2.log`. The two-owner broad corpus also passes and
matches all **136 windows exactly** against its original queued-context version
(`/tmp/ls-turnover-corpus-2.log` versus `/tmp/ls-queued-corpus-2.log`). Thus neither
capacity introduces a regression in the measured corpus; the handoff specifically
repairs the four-owner tail penalty and improves its larger load-fed burst.
Each two/four-owner selected correctness set passes **48 tests**, including changing
source/slot reuse (120 queued admissions, 144 captures), 16 multi-owner redirect
cancellations, back-response contention, alias/split and precise-I/O controls,
faults, NZVC, repeated-RTS IRQ, CCR/RTE and overflow/DIVREM. Evidence:
`/tmp/ls-turnover-oracle-{2,4}.log`. Production four-owner RTL generation also
passes (`/tmp/ls-turnover-production.log`); it is not a synthesis result. The
serialized mandatory `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` passes
**387 tests, two ignored, zero failures** (`/tmp/ls-turnover-fast.log`). The
separate shared boundary-33 sweep is not covered by these passes.

Next correctness diagnostic: trace the actual IPL assertion, `p0.first`,
`irqPreemptArmed`, `inhibitedLoadBusySig`, exception state and retired macro PCs
at boundary 33. The RTL intentionally holds inhibited-load busy through its
retirement and prevents new first-uop inhibited launches while an IRQ is armed;
those safety conditions must not be removed just to satisfy a two-instruction
fixture window. Conversely, a missing macro-first marker or stale busy qualifier
must not be hidden by widening that window. Keep full register/CCR/frame/local
memory comparison when matching the independently observed accepted boundary.

Implementation committed as `f6df3ecf`. After validation, the still-waiting
`b4c99f82` physical screening was superseded before it created any arm or launched
Vivado (its artifact directory remained empty). The replacement is
`m68k-ls-queued-turnover-gate.service`, pinned to
`f6df3ecf009c6580107e8911cfc5076bc5f3ad7c`, artifacts
`/tmp/ls-turnover-gate.Pe092A`. It runs fresh matched one/two/four-owner arms after
early NZVC, under the same Vivado mutex, comparing the refined queue rather than
spending physical runs on its superseded handoff. Both source revisions and all
before/after simulation results remain preserved. No board actions occurred.

### Publication-forward timing completed in the background

Pinned `3f53a39b` publication-forwarding now completes its core-only routed
comparison: baseline **−0.073 ns**, candidate **+0.045 ns**, at a real 5.000 ns
constraint. The candidate has **zero setup, hold or pulse-width violations**,
hold slack +0.019 ns and pulse-width slack +1.958 ns. LUTs **94,769→93,917**;
FFs **38,759→38,761**; BRAM tiles remain 37. Worst candidate path is ALU opcode
bit 0 to LSU completion NZVC bit 2, 21 levels, 63.96% routing. This confirms
core-only timing for the earlier IPC-positive publication-forward candidate;
it does not establish queued-context, early-NZVC, integrated SoC or board timing.
Artifacts: `/tmp/sq-publish-gate.GHXuam/{baseline,published}-summary.txt`,
`published/synth/fullcore_route_{timing,util}.rpt`. The recovery service exits
successfully; the serialized early-NZVC comparison has advanced next.

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

## Integrated 100 MHz diagnostic candidate — 2026-09-21

User requested a combined board build rather than more proposal screening.
`CPU_IPC_PROFILE=throughput-v1` now wires the measured LSU fall-through, early
integer/NZVC wakeups, early store address, long-MOVE fusion, subword forwarding,
late-SQ reservation, detached stores, publication forwarding and four-context
queue into the real socket. Selective slot-1 prediction and retained redirect
history are enabled together. Retirement remains ordinary two-wide. Defaults
remain baseline; unknown profile names fail. The SoC records profile and CPU
revision in build metadata. The 100 MHz diagnostic SoC baseline is `462a4dc`;
retain its Ethernet, counters, ILA and storage settings for comparison.

Composed profile (including subword forwarding): **56 selected oracle tests
pass**, `/tmp/ipc-socket-oracle.log`. Socket/reset/debug tests: **73 pass**,
`/tmp/ipc-socket-reset-tests.log`. Actual detailed socket RTL generation passes,
`/tmp/ipc-socket-generation.log`. The structural checker now explicitly checks
the existing group-7 debug port schema and opt-in 95-bit performance trace;
the frozen full-core port baseline is unchanged. Generated candidate passes
(`/tmp/ipc-socket-structure.log`). These are not new board performance results
or integrated timing acceptance.

Mandatory `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` passes **388 tests,
two ignored, zero failures**, `/tmp/ipc-socket-fast.log` (10:10:47 local).

The separate odd-SSP IRQ boundary-33 issue remains open. Bounded tracing
(`/tmp/irq-boundary33-gates-1.log`) shows load-busy and precise-drain-busy false
throughout deferral. ROB head remains non-first while dual retirement advances
from PCs 408000a2 through 408000ae; recognition occurs at first-uop head
408000b2. This points toward macro boundaries crossed by retirement lane 1,
not stale memory-busy state, but does not yet prove a precise-state defect or
justify widening the oracle's acceptance window. `IRQ_GATE_TRACE` and
`ODD_SSP_BOUNDARIES` only bound diagnostics; defaults and checks are unchanged.
This baseline-shared failure is not represented as fixed by the candidate.

Board candidate pins: CPU `8fab36144b9181828a6d234346c2d02c4c277eed`, SoC
`6b10354af8e8a43eed7ec5c3b5f184eee4964f11`. SoC workspace
`/home/qwertyoruiop/macqd700-soc-worktrees/ipc-candidates-100mhz`, service
`m68k-ipc-candidate-soc100.service`, runner `/tmp/run-ipc-candidate-soc100.sh`.
Full top-level Ethernet/performance/ILA lint and real-MIG lint pass, as do
storage-reset pairing and synthesis source-list checks. The initial lint
attempt hit only Taxi unnamed-generate style warnings: placing the existing
Ethernet lint exception after `-Wall` fixes the command, with no RTL change.
Logs `/tmp/ipc-candidate-soc100-{build,generation,lint}.log`; existing tmux
`build-logs` follows them. At 10:13 local the job waits on the shared Vivado
mutex behind the running early-NZVC physical arm. It holds that mutex throughout
implementation, using a separate inner Make lock to avoid recursive locking.
No board programming has occurred. Verify full timing and locally patch ADB
firmware before the user-authorized volatile load; do not flash SPI or touch SD.

Additional composed-profile IPC matrix is running serially after the fast gate:
`/tmp/ipc-socket-composed-corpus.log`, with subword forwarding plus retained
history/selective prediction, all LSU options and four contexts. Compare by
kernel/seed/fall-through/early-wakeup against `/tmp/ls-turnover-corpus-4.log`;
do not mistake different knob combinations for matched results.

### Composed-profile simulation and fresh board baseline

The additional simulation matrix completes successfully. Against the recorded
four-context LSU corpus, the composed subword/predictor configuration has **8
improvements, 128 identical cases, zero regressions**, matched by kernel, seed,
fall-through and early-integer-wakeup mode. All eight changed cases are the hot
loop: **326→317 cycles (+2.84% IPC)**, for both seeds in all four mode combinations.
This measures the composition, not an isolated attribution to either added knob.
The socket uses the both-enabled mode. No broad board speedup is inferred.

At 10:15 local a nonintrusive pre-load sample on live build `462a4dc1` measured
0.104879/0.104965/0.104838 IPC. The user subsequently confirmed Dhrystone was
**not running**; those samples must not be used as its baseline. The matching
1024-cycle ILA captured 90 macros and predominantly PCs around 0007bbxx.
Raw results remain in `/tmp/jtag_out`, from `BEFORE_CANDIDATE build=` through
the exact `IPC_BEFORE_CANDIDATE_DONE_20260921` line; capture is
`/tmp/ipc-before-candidate-20260921.csv`. The first shell collector accidentally
matched the echoed Tcl source's sentinel string; it did not interrupt the Tcl
experiment. Matching the full sentinel line fixes collection. No reset/halt.

The user restarted and confirmed Dhrystone at 10:19. Fresh live-build-verified
one-second windows, `/tmp/ipc-dhrystone-before-candidate-20260921.log`:

| Window | Counted cycles | Retired macros | IPC | Head load | Head store | IQ blocked |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 100,218,040 | 23,063,857 | 0.230137 | 17.418% | 27.889% | 56.907% |
| 1 | 100,198,686 | 23,205,408 | 0.231594 | 16.716% | 29.422% | 57.260% |
| 2 | 100,253,311 | 23,995,178 | 0.239345 | 16.424% | 29.940% | 59.086% |

ROB-full count is zero in all three windows. Saved ILA:
`/tmp/ipc-dhrystone-before-candidate-20260921.csv`, 1024 consecutive cycles,
200 retired macros (0.195312 IPC in this short sample), 334 head-store cycles,
161 head-load cycles, 660 IQ-blocked cycles, zero ROB-full cycles. Frequent PCs
03be19c2/19c6/19ca/19cc repeat 27 times each. The ILA window is not the one-second
counter denominator and must not be concatenated with it. JTAG lease released;
the board remains running. The user has authorized the later candidate load.

The boundary-33 lane trace now confirms the deferral mechanism:
`/tmp/irq-boundary33-lanes.log`. With IPL1 unmasked and no device/drain busy,
lane 0 retires a last/non-first uop while lane 1 retires the first uop of the next
macro, repeatedly at 408000a2→a6→aa→ae. Recognition waits until a first uop
finally becomes head at 408000b2. This is evidence of a skipped recognition
opportunity, not evidence of stale A7 data. No production fix is included in
the pinned board candidate. Investigate a cold-path macro-boundary retirement
barrier for active unmasked IRQ/NMI, including wider/prepared retirement;
preserve existing fault priority and precise-memory draining.

The lane-trace-only test change passes the mandatory fast gate: **388 tests,
two ignored, zero failures**, `/tmp/ipc-post-board-preflight-fast.log`, completed
10:22:15 local. It does not change the pinned CPU/SoC build. The IRQ reproducer
still fails its original exact comparison; its failure has not been waived or
converted to a pass. The next safe investigation is a directed boundary barrier
prototype, separately gated and IPC-compared before any integration decision.

### IRQ-boundary barrier prototype

The prototype finishes the current macro but prevents a retirement group from
crossing into the next macro while an unmasked level/NMI request is active.
It reuses the existing request condition, adds no pending-state register,
preserves the precise-memory recognition gates and conservatively aborts
prepared publication. The interrupt design contract has been amended.

Original failing boundary-33 now passes its unchanged exact oracle comparison
(`/tmp/irq-boundary33-barrier.log`), recognizing at 408000a6 rather than skipping
through 408000b2. The composed profile passes **29 selected oracle tests**,
including the complete boot-0 LINK boundary sweep, repeated RTS, precise-device
IRQ replay, CCR/RTE storms and the selected store/forwarding controls
(`/tmp/irq-boundary-composed-oracle.log`). Ordinary four-wide and prepared-eight
boundary-33 runs each pass (`/tmp/irq-boundary-{four,prepared8}-oracle.log`).
**12 unit/race tests pass** (`/tmp/irq-boundary-unit.log`), including a new
33-scenario test spanning every lane boundary for 2/4/prepared8 retirement,
unmasked IRQ, masked control, NMI and prepared-image cancellation.

Matched no-IRQ IPC: **all 136 cases identical** to the pre-barrier composed
profile (`/tmp/irq-boundary-ipc.log` versus `/tmp/ipc-socket-composed-corpus.log`).
Required fast gate passes **388 tests, two ignored, zero failures** at 10:39:19
local (`/tmp/irq-boundary-fast.log`). No timing claim or board inclusion: the
delegated SoC build remains pinned to `8fab3614`/`6b10354`.

### Board-derived byte-copy benchmark preparation

User reconfirmed Dhrystone running. Read-only DDR sampling and a supervisor
page-table walk locate the frequent trace PCs in this dependency chain:

```asm
movea.l 12(a6),a0
addq.l  #1,12(a6)
move.b  (a0),(a1)+
bne.s   loop
```

Observed opcodes at 03be19c2 are `206e 000c 52ae 000c 12d0 66f4`, independently
decoded with GNU m68k objdump. TC=c000; descriptor reads are
03fffa04→03ff760a, 03ff77bc→03ff060a, 03ff0640→03be0039, establishing the sampled
supervisor VA=PA=03be19c2 mapping. Logs `/tmp/ipc-dhrystone-{hotcode,translation}.log`.
The running I-cache debug probe timed out; it supplied **no cache contents or
residency evidence**, and no halt/cache maintenance was performed. JTAG released.

`BoardStringCopyIpcSpec` reproduces this four-instruction dependency shape with
32/128-byte deterministic nonzero patterns plus NUL, two seeds, baseline versus
the complete socket option set. Paired readback variants check every copied
byte, while both variants check the final source/destination pointers. Eight
iterations are excluded as warmup. This is not the full Dhrystone executable or
an exact board memory-layout replay; no speedup is claimed before it runs.

The previously reported combined synthetic aggregate uses only the both-enabled
fall-through/early-wakeup mode: **10,986 macros / 35,995 cycles = 0.305209 IPC**,
17 kernels × two seeds. It is not an average across four alternative core modes.
A fresh no-feature control using the same kernels/memory/seeds is queued after
the IRQ gates (`m68k-ipc-fresh-baseline.service`); the board-loop experiment follows
serially (`m68k-board-copy-ipc.service`). This will provide a matched aggregate
comparison, not an invalid comparison against board Dhrystone's 0.233693 IPC.

The actual board ILA gives a concrete target: the copy-loop load PC repeats with
**24-cycle spacing in 25 of 26 observed intervals** (one interval is 43 cycles).
Within each ordinary iteration, the RMW pointer increment retires six cycles
after the pointer load, the byte copy eleven cycles after that, then the branch
three cycles later; the next iteration starts four cycles later. Thus this
observed four-macro loop sustains about **0.167 macro IPC / 24 cycles per byte**
on the baseline, distinct from whole-window Dhrystone IPC. In capture cycles
[24,643), 619 cycles contain 100 macro retirements, 250 head-store stalls,
85 head-load stalls, 50 head-branch stalls and one misprediction. This window
provides no evidence of a retirement-width limit (zero ROB-full cycles).

At the user's explicit request, `/root/ipc_soc100_finish` owns the live SoC job,
artifact/timing audit, local ADB patch and authorized volatile load. Parent owns
serial simulations and IPC RTL work; no concurrent parent board access.

### Sep 21: matched control, board-copy results and cracked uops

Fresh no-feature corpus completed: 136 cases across the four fall-through/wakeup
settings pass (`/tmp/ipc-fresh-baseline-corpus.log`). Compare only the 34 fully
disabled cases against the composed profile's 34 both-enabled cases, using the
same 17 kernels, seeds 1/17 and L2 5-cycle / DDR 70-cycle model:

| Profile | Macros | Cycles | Aggregate macro IPC |
| --- | ---: | ---: | ---: |
| Fresh baseline | 10,986 | 41,809 | 0.262766 |
| Combined | 10,986 | 35,995 | 0.305209 |

Aggregate gain **16.1522%**; 27 cases improve, six unchanged, one regresses:
`mixed`, seed 1, 792→808 cycles (about 1.98% lower IPC). This is a matched
synthetic comparison, not a Dhrystone or integrated-board result.

`BoardStringCopyIpcSpec` passes all 16 runs (two profiles, two lengths, two
readback choices, two seeds), including byte-for-byte destination and pointer
checks. Non-readback windows, after eight warmup iterations:

| Length / seed | Window macros | Baseline cycles / IPC | Combined cycles / IPC |
| --- | ---: | ---: | ---: |
| 32 / 1 | 102 | 645 / 0.158140 | 553 / 0.184448 |
| 32 / 17 | 102 | 646 / 0.157895 | 549 / 0.185792 |
| 128 / 1 | 486 | 3338 / 0.145596 | 2871 / 0.169279 |
| 128 / 17 | 486 | 3333 / 0.145815 | 2868 / 0.169456 |

IPC gains 16.2–17.7%; the separate readback variants also improve 16.7–17.9%.
Log `/tmp/board-copy-ipc.log`. These windows include loop exit and final pointer
reads; they are not an exact per-iteration latency or the original executable.
Both profiles still perform the same number of stores: 67 or 259 including
initialization. Both peak at two SQ residents and one accepted drain. The
128-byte/seed-1 oldest-store-unready window shrinks 2641→2172 cycles, but remains
large. This motivates dependency-latency work, not an assertion that SQ capacity
or retirement width is the limiting resource.

`BoardLoopDecodeTraceSpec` replays the exact board-read opwords at 03be19c2
through I-cache, fetch alignment, DecodeStage and the rename-facing uop queue.
The trace uses cold/not-taken prediction to emit one sequential pass. It is
decoded RTL output, not a live-board uop/PRF-tag capture. The initial combined
fixture lacked the required secondary prediction service; adding a test-owned
cold prediction provider fixes the fixture, with no production RTL change.
Both profiles now pass (`/tmp/board-loop-decode.log`). Actual sequence:

```text
PC 03be19c2 MOVEA.L 12(A6),A0
  baseline: LOAD.L [A6+12] -> T0; MOVE.L T0 -> A0
  combined: LOAD.L [A6+12] -> A0
PC 03be19c6 ADDQ.L #1,12(A6)
  LOAD.L [A6+12] -> T0
  ADD.L T0,#1 -> T1, NZVC, X
  STORE.L T1 -> [A6+12]
PC 03be19ca MOVE.B (A0),(A1)+
  LOAD.B [A0] -> T0
  STORE.B T0 -> [A1], A1 := A1+1, NZVC
PC 03be19cc BNE.S 03be19c2
  BRANCH using NZVC from the byte store
```

Four macros become **eight baseline uops, seven combined uops**. T0/T1 are
logical renamed temporaries, not one physical register reused serially across
all these instances. Both profiles retain five LS uops per iteration. The byte
store produces both the architectural A1 update and the branch's flag input;
dropping a redundant memory transaction cannot drop these results. The baseline
eight-uop count independently agrees with the board ILA's 200 retired uops per
100 macro instructions in the selected steady-loop window.

Store coalescing is recorded as proposal 18, with a conservative committed,
adjacent, unpresented COPYBACK-only first experiment. No RTL implementation or
speedup claim. Pointer stores in this loop are nonadjacent in store order, so the
minimal cheap version would not directly merge them.

Physical queue update: the independent early-NZVC core gate completed with
WNS +0.004 ns, hold +0.024 ns, pulse +1.958 ns, zero failing endpoints; baseline
0.000/+0.019/+1.958 ns. Reports under `/tmp/ls-nzvc-gate.cM6rEn/`. This is core
timing only. The delegated pinned 100 MHz SoC build acquired the global mutex
at 10:44:25 local and started; no new board performance result yet.

Required post-test fast gate passes **388 tests, two ignored, zero failures**
at 10:52:41 local (`/tmp/board-loop-fast.log`). No production RTL changes in this
measurement/trace addition.

User's next priority: next-cycle wakeup of a load dependent on a store. Define
the measured event precisely: known youngest overlapping older store, full byte
coverage, resolved permission/cacheability, reserved SQ ownership, data becoming
available, and a guaranteed forwarding/completion resource. Then wake the load
without waiting for ROB retirement or cache drain. Address-unknown, partial or
multi-store coverage, device, split and fault cases remain conservative.

Current `forwardOnPublish` bypass already makes newly published store data
available to an SQ query in that cycle. It does **not** implement a tagged memory
dependency wakeup into the IQ. LS still selects the oldest occupied LS uop;
forward results are captured at P3→P4 or a P4 retry, then completion is selected
from the registered verdict. `earlyIntWakeup` announces that selected completion's
next-cycle PRF write, not the original store's data-production event. The next
experiment should distinguish producer-data→SQ-publication, publication→load
completion, and load-completion→consumer-issue intervals; shortening one is not
evidence that the entire chain is one cycle. No one-cycle implementation claim yet.

### Sep 21: remove the postincrement-store admission restriction

Baseline is `445a2f3d`, containing the exact decoder replay, paired board-loop IPC
and passing 388-test fast gate. The pinned SoC build remains separate and unchanged.

Bounded `IPC_LS_EVENTS=board-byte-copy` simulation traces now join LS issue PC/ROB
identity, SQ reserve/publication, forwarding and completion. A short diagnostic
run is selected by `IPC_BOARD_COPY_TRACE=1`; it does not replace the default full
copy/readback matrix. Logs `/tmp/board-copy-ls-events.log` and
`/tmp/board-copy-auto-store-probe.log`. Query hits are raw observations, not an
accepted-load count; use `forwardComplete` for actual P4 completions.

Important finding: previous combined steady iterations take 20 cycles and do
not use SQ forwarding for the pointer loads. The intervening byte postincrement
store waits for its source in IQ and blocks otherwise-ready younger loads;
by the time they query the SQ, the pointer store has already drained. Thus simply
speeding up an SQ-hit path would miss the main measured opportunity.

Default-off `earlyAutoStoreAddress` admits ordinary postincrement MOVE stores
once their address sources are ready. It extends the existing detached context
by the precomputed An result, physical destination, architectural destination and
valid bit (44 logical payload bits per context; not a routed area estimate).
Integer/NZVC results still publish only at selected normal completion. No new
PRF port, data array, early architectural update or second completion owner.
The IQ classifier, LSU guards and design contract change together. Enable with
`IPC_EARLY_AUTO_STORE=1`, `LOCKSTEP_EARLY_AUTO_STORE=1`, or core generator option
`--early-auto-store-address`, alongside early store address/reserve/detach.
The SoC throughput-v1 profile is deliberately unchanged.

Matched full-core L2:5/DDR:70 results, same kernels and seeds as the previous
combined profile (`/tmp/board-copy-ipc.log` versus
`/tmp/board-copy-auto-store-full.log`):

| Copy window | Macros | Previous cycles | Candidate cycles | IPC gain |
| --- | ---: | ---: | ---: | ---: |
| 32 bytes, seed 1 | 102 | 553 | 384 | 44.01% |
| 32 bytes, seed 17 | 102 | 549 | 381 | 44.09% |
| 128 bytes, seed 1 | 486 | 2871 | 2058 | 39.50% |
| 128 bytes, seed 17 | 486 | 2868 | 2046 | 40.18% |

The four paired readback cases improve 36.68–38.55%; all 16 full runs pass,
including every copied byte, NUL and final source/destination pointers. The
candidate observes 25/121 actual reservation/publication events in the respective
steady windows, asserted nonzero by the benchmark. Baseline no-feature cycles
are unchanged. The trace now shows 14-cycle steady iterations and real pointer
SQ forwards. This is simulated board-derived code, not a new board measurement.

The initial oracle run passed architectural state but failed final memory in
the new A7 test: the independent final-memory oracle kept executing past the
program with A7 pointing into the checked data page. Added an explicit stop loop
and kept the same compared macro prefix and byte checks. Preserved failed log:
`/tmp/auto-store-oracle-before-stop.log`. Rerun passes **29 selected oracle tests**
(`/tmp/auto-store-oracle.log`), including 12 delayed postincrement captures in
both cacheable and precise modes; copyback reserves/publishes eight, precise
mode reserves none. Byte/word/long, A0/A7 byte stride, line crossing, cancellation,
write-protection, device ordering and IRQ/CCR cases are covered. No failure was
waived. The broad 136-case IPC run passes with **all cycle counts identical** to
`/tmp/irq-boundary-ipc.log` (`/tmp/auto-store-ipc.log`, completed 11:14:16 local).
Its existing kernels do not expose this postincrement-store restriction; the
unchanged 0.305209 aggregate must not be presented as the new copy-loop IPC.
The mandatory fast gate passes **388 tests, two ignored, zero failures** at
11:17:56 local (`/tmp/auto-store-fast.log`). Physical timing remains untested.
The next gate compares the same four-context combined core with the postincrement
option off/on; SoC sources and the live board are unchanged. A bounded
`short-store-load-recurrence` trace is running after the completed validation
sequence to resolve producer-data, publication, load completion and wakeup edges.

Candidate committed as `80f408b224d7ddfbd5d5ddb3587b25a85bb20cf6`. Matched
200 MHz gate queued as `m68k-auto-store-timing-gate.service`, artifacts
`/tmp/auto-store-gate.DYMWvp`, after `m68k-ls-queued-turnover-gate.service`.
Both arms use the same four-context profile with subword forwarding; only
`--early-auto-store-address` differs. The global Vivado mutex remains in use.
Queued is not synthesized, routed or signed off.

The bounded recurrence trace passes all eight mode/seed runs
(`/tmp/store-load-wake-trace.log`, 11:20:12 local). In the full combined mode,
seed 1, it directly records this uncontended chain:

| Cycle | Event |
| ---: | --- |
| 188 | Producer load selects integer wakeup for physical register 21 |
| 189 | Producer load ROB 2 writes register 21 |
| 190 | Reserved store ROB 3 publishes; dependent load ROB 4 sees publication-edge SQ hit |
| 191 | Load ROB 4 selects completion and integer wakeup for register 22 |
| 192 | Load ROB 4 writes register 22 |

Thus a translated waiting load already announces readiness one cycle after SQ
publication when completion is uncontended. This is two cycles after the store
source's physical value becomes available, not a one-cycle total producer chain.
The extra source-readiness cycle is visible in RTL: IQ's `queryReady` reads the
registered dynamic busy maps, then the LSU registers `readyPrior` before using
its existing PRF read port. Next experiment: qualify that register with the
matching guaranteed producer wakeup as well as the already-ready map, preserving
same-cycle PRF write/bypass, tag lifetime, read-port exclusion and squash safety.
Do not remove the register or predict readiness without proving those contracts.
This is a proposed follow-up, not part of `80f408b2` or the pinned SoC.

### Wake-qualified late store-data capture — 2026-09-21

Implemented the follow-up as default-off `earlyStoreDataWake`, exposed through
`--early-store-data-wake`, `IPC_EARLY_STORE_DATA_WAKE=1` and
`LOCKSTEP_EARLY_STORE_DATA_WAKE=1`. IQ alone produces `queryReadyNext`; LSU keeps
its registered qualification and existing read port. Matching LS/CPLX wakes may
qualify capture before the registered busy bit clears. Inspection found that
slow ALU announces TWO cycles before writeback, so it explicitly retains the
old delay. Both detached and P3-held owners use the new optional qualification.
Tag lifetime, flush/exception veto, admission reset and port-exclusion assertions
remain. No change to the SoC throughput-v1 pin.

Matched L2:5/DDR:70 copy results versus the postincrement candidate:

| Copy window | Macros | Prior cycles | New cycles | IPC gain |
| --- | ---: | ---: | ---: | ---: |
| 32 bytes, seed 1 | 102 | 384 | 360 | 6.67% |
| 32 bytes, seed 17 | 102 | 381 | 360 | 5.83% |
| 128 bytes, seed 1 | 486 | 2058 | 1945 | 5.81% |
| 128 bytes, seed 17 | 486 | 2046 | 1945 | 5.19% |

Readback windows: 428→409/403 cycles for 32 bytes, 2214/2208→2104/2098
for 128 bytes. All 16 runs pass, checking every copied byte, NUL and final
pointers (`/tmp/early-store-data-copy.log` versus
`/tmp/board-copy-auto-store-full.log`). The initial bounded probe is
`/tmp/early-store-data-copy-probe.log`; the full matrix uses the final
slow-ALU-safe qualifier.

All 29 selected oracle tests pass (`/tmp/early-store-data-oracle.log`, 11:35
local), including 16 precise-mode and 12 copyback-mode captures immediately
following a matching early qualification, asserted nonzero. Existing shift,
divide, load, byte/word/long, crossing, protection, redirect, device and IRQ
cases remain checked. Completion-contention coverage observes three held
publications and three recovered completions with correct identity/NZVC.

All 136 broad IPC runs pass (`/tmp/early-store-data-ipc.log`, 11:38:32).
Compared with `/tmp/auto-store-ipc.log`, 36 improve and 100 are unchanged;
none regress. For the actual combined mode alone: 10,986 macros in
35,995→35,925 cycles, **0.305209→0.305804 IPC (+0.195%)**, eight gains and
26 unchanged. Do not blend alternate option profiles into that aggregate.
The divide-fed recurrence improves 2336→2304 cycles. The short load/store
recurrence stays at 286/285 cycles with early LS wake already enabled, despite
less owner waiting; without early LS wake it improves 344/343→286/285.
Earlier qualification does not remove context admission, issue throughput or
shared completion constraints. In particular the first C188 reservation cannot
inherit readiness on its admission edge: its C190 publication/C191 load wake
remain unchanged. This is not a claim that every producer-to-load chain is now
one cycle. The final broad log contains the cycle trace for both seeds/modes.

Mandatory `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` passes:
388 tests, two ignored, zero failures (`/tmp/early-store-data-fast.log`,
11:42:08 local). Matched 200 MHz physical gate will compare this same
four-context/postincrement/subword profile with only the new qualifier toggled,
after the already-queued postincrement comparison. No timing result yet.

Candidate pin `626a0debb11a50e26a6a15cd04389095f94b1218`; matched gate queued as
`m68k-store-data-timing-gate.service`, artifacts `/tmp/store-data-gate.uHJWYd`.
It waits for `m68k-auto-store-timing-gate.service`, generates isolated pinned
baseline/earlydata worktrees, and retains the global Vivado mutex. The existing
`build-logs:0.0` pane now follows the active context-capacity baseline run.

### Combined 100 MHz SoC loaded — 2026-09-21

SoC `6b10354af8e8a43eed7ec5c3b5f184eee4964f11`, CPU
`8fab36144b9181828a6d234346c2d02c4c277eed`, throughput-v1, Ethernet/perf/ILA
enabled. Final reports: setup +0.034 ns, hold +0.010 ns, pulse 0.000 ns,
zero setup/hold/pulse failures; 44 bus-skew constraints, minimum +2.451 ns.
DRC warning classes/counts match baseline, no errors/critical DRC. Existing
external-I/O/duplicate-clock warnings remain; no unconstrained internal endpoints
were reported by the build agent. Routed LUT 153,976→153,297 (−679),
FF 95,193→95,475 (+282), BRAM/URAM unchanged. This is a 100 MHz SoC result,
not a 200 MHz signoff.

Local ADB-patched bitstream was loaded through the existing leased JTAG session;
readback confirms build ID `0x6b10354a` and the expected probes. No SPI or SD
write. Build log `/tmp/ipc-candidate-soc100-build.log`, programming transcript
`/tmp/ipc-candidate-soc100-load.log`. The host tail/awk reader buffered the
completed reply; stopped that reader after independently verifying programming
and READY in `/tmp/jtag_out`. This did not repeat programming; lease released.
The build agent stopped before sending the load, so root completed the already
authorized volatile load. User asked to restart Dhrystone before matched counter
windows. No post-load IPC claim yet. Proposals 19/20 and the subsequent IRQ
boundary correction are not included in this older, deliberately pinned image.

Post-load acquisition at 11:45 was invalidated: user clarified Dhrystone was
not running. Do NOT compare `/tmp/ipc-dhrystone-after-candidate-20260921.log`
or its matching CSV with the Dhrystone baseline, despite the raw script's
incorrect `workload=user-confirmed` label. Correction is preserved in the
matching `.INVALID.txt` sidecar. No CPU halt/reset; counters restored and lease
released. A fresh uniquely named acquisition requires running confirmation.

### Confirmed Dhrystone board comparison — 2026-09-21 11:47

User explicitly confirmed Dhrystone running, then reported **46k Dhrystones/s
at 100 MHz versus 35–36k previously (+27.8–31.4%)**. Fresh acquisition verifies
build `6b10354a`: `/tmp/ipc-dhrystone-confirmed-after-20260921.log` and matching
CSV. Three one-second running-counter windows, same counter protocol as baseline
`462a4dc1`; snapshots freeze counters only, never the CPU. Restored original
counter enable and released JTAG. Earlier invalid acquisition remains excluded.

| Window | Core cycles | Retired macros | IPC |
| --- | ---: | ---: | ---: |
| 0 | 100157885 | 30076445 | 0.300290 |
| 1 | 100192428 | 29974769 | 0.299172 |
| 2 | 100184044 | 29838228 | 0.297834 |

Weighted IPC **0.233692867→0.299098722 (+27.99%)**: baseline 70,264,443
macros / 300,670,037 cycles; candidate 89,889,442 / 300,534,357. This is a
user-confirmed full-system Dhrystone window, not an isolated-PC counter filter.

| Weighted metric | Baseline | Combined candidate |
| --- | ---: | ---: |
| Mispredictions per 1000 retired instructions | 44.448 | 26.047 |
| Head-load stall cycles | 16.853% | 14.246% |
| Head-store stall cycles | 29.084% | 31.016% |
| Head-branch stall cycles | 3.078% | 2.089% |
| Head-other stall cycles | 9.928% | 5.514% |
| ROB empty | 6.476% | 4.866% |
| Dispatch blocked by IQ | 57.752% | 57.224% |
| ROB full | 0% | 0% |

MPKI is per instruction, NOT per branch, and does not establish 95% predictor
accuracy. Return mispredictions increased from about 8.06 to 9.54 per thousand
instructions while other mispredictions fell substantially; retain that regression
in future predictor evaluation. Store-head share increased but store-stall cycles
per retired instruction decreased; percentages alone are not a latency regression.
Store waiting and IQ pressure remain the main visible opportunities.

Immediate nonintrusive ILA contains 1024 cycles / 382 retired macros, local IPC
0.373047. It sampled a different mix from the baseline capture, so do not claim
that local IPC ratio as the overall improvement. Its seven retirements at
`03be19c2` yield six consecutive 20-cycle copy-loop intervals, versus the prior
24-cycle steady baseline; this agrees with the pre-postincrement simulation.
The two newer store experiments are NOT included in this board result.

### Direct 200 MHz integrated candidate — 2026-09-21

User requested going straight to a 200 MHz SoC, rather than another 100 MHz
image. Added explicit socket `throughput-v2`: v1 plus postincrement late-store
reservation and wake-qualified data capture. V1/default behavior remains
unchanged. IQ/LSU enable the matching postincrement contract; the socket has no
new external signals. CPU pin `ca3f31da7f04cdfbcbc7199d4ce7683e35638403` also
includes the already-validated precise IRQ macro-boundary fix. Profile selection
tests cover baseline/v1/v2 and reject unknown settings. Mandatory fast gate:
388 passed, two ignored, zero failures at 12:19:19
(`/tmp/ipc-throughput-v2-fast.log`). Earlier 29-case oracle and 136-case IPC
results apply to this same option combination, not a new timing claim.

Isolated SoC worktree
`/home/qwertyoruiop/macqd700-soc-worktrees/ipc-candidates-200mhz`, branch
`perf/ipc-candidates-200mhz`, pin `d3b19b2dfa7168265caf303ba4ef860801d32c8d`.
Service `m68k-ipc-candidate-soc200.service` started 12:20 local; runner
`/tmp/run-ipc-candidate-soc200.sh`. Core/fabric target 200 MHz, peripheral clock
50 MHz, real cached MIG, Ethernet, detailed counters, IPC ILA, VIO/JTAG AXI
and SCSI trace retained; PCIe off, CMD25 off. No incremental implementation.
Existing AggressiveExplore route/post-route flow retained. Netlist generation,
top/real-MIG lint, reset-pairing and synthesis-source checks precede synthesis.

Priority: suspend ONLY the context-capacity queue's parent shell, not its active
two-context arm/Vivado child. Once that arm releases the global mutex, the full
SoC takes the next physical slot instead of waiting for all remaining core A/B
comparisons. The SoC runner resumes that parent on exit (including failure),
allowing the pending four-context/postincrement/wakeup comparisons to continue.
This is build scheduling, not a paused goal. No running implementation was killed.

Logs `/tmp/ipc-candidate-soc200-{build,generation,lint}.log`; the existing
`build-logs:0.0` pane follows the full-SoC job and the active core arm. Final
timing/DRC and local ADB patch remain required before any load, which needs
fresh permission. The Mac stays on build `6b10354a`; no SPI/SD writes or
automatic programming are part of this job. Started/preflight is not routed
timing acceptance or a completed bitstream.
