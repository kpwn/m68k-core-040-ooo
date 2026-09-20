# IPC experiment ledger

Record every performance candidate here, including unsuccessful and zero-gain
experiments. Before/after measurements must use the same workload, seeds,
memory configuration and retirement window. An unmeasured candidate is pending,
not an improvement. Simulation IPC is not board IPC or proof of preserved Fmax.

For each new experiment record:

- Baseline and candidate revisions, dirty changes if any, and option settings.
- Workload, memory model, seeds, warm-up and exact measured macro/cycle counts.
- Baseline/candidate IPC and percentage change for each workload, including regressions.
- Correctness checks, test results and reproducible commands.
- Matched routed timing and area, or explicitly pending; acceptance/rejection.

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
| Guaranteed early integer wake, `5c971456` | See early-wake column | Routed result pending; default off. |
| Combined fall-through + early wake | See combined column and full-corpus ranges | Repaired combined implementation in timing queue; default off. |
| Payload-selection timing repair, `b99636f4` | **0% change:** all 104 benchmark rows have identical macro and cycle counts before/after | Timing pending; no Fmax recovery claimed. |
| Macro-counter correction, `b0eeabaa` | Measurement correction, not an RTL speedup; removed extra call/return counts | Use corrected figures only. |
| Warm-window harness extension, based on `b99636f4` | **0% change:** all 104 existing benchmark rows unchanged with default warm-up 0 | Test-only change; new baseline below. |

Timing above is core out-of-context at 5 ns on `xcku5p-ffvb676-2-e`, not complete
SoC signoff. Hold and pulse width passed in the original fall-through run.
The repaired baseline/early-wake/combined matrix is still running.

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
