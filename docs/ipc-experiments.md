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
`build-logs:0` pane follows both elaboration and implementation logs. No routed
result is available yet, and the combined fusion/early-store configuration will
still need its own timing gate.
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
defaults remain off; no route or board improvement is claimed yet.

Implementation: `6d8a7a7de491f1e9a10ff74dabc33e67a2411571`. Unit
`m68k-sq-reserve-6d8a7a7d.service` queues matched core routing behind direct-load
fusion, using the shared Vivado mutex, 5 ns and three post-route rounds. Both
arms enable aligned-load fall-through, early integer wakeup, early store address
and direct long MOVE loads; only the candidate enables late-store SQ reservation.
Thus the baseline also gates the previously untimed fusion/early-store combination.
Artifacts: `/tmp/sq-reserve-gate.sU6Lpv`; the existing `build-logs:0` pane follows
both elaboration and implementation logs. This queue does not stall the next IPC
experiment, and no timing result is available yet.

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

Next simple latency question: when an owner is reserved, it currently discards
the source-readiness qualification being observed for that very P3 store. If the
source becomes ready during address resolution, the new owner may wait an extra
cycle merely to restart the same registered qualification. Test safe transfer of
that decision on admission, using the same source tag and existing register,
without adding speculative readiness or another PRF port. Also quantify how often
one pending owner blocks another ready address before expanding owner capacity.

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
