# Resident-load latency experiment

## Guaranteed next-cycle integer wakeup candidate

An additional, separately disabled option announces successful LS integer
writeback one cycle before the existing registered writeback stage. It is not a
cache-hit prediction: the completion arbiter has already selected a successful
result, and that exact physical destination will be written in the next cycle.
The data, flag, ROB-completion and architectural-retirement paths do not move.

This relies on the IQ's registered dependency clear and registered selection:
an awakened consumer cannot reach operand capture before that writeback. Pending
dispatch dependencies must observe the same guarantee. Faults and orphaned
precise stores never announce a result; flush retains normal IQ cancellation.
A cycle-by-cycle simulation assertion checks each announcement against actual
next-cycle writeback eligibility and destination. Full-core tests must exercise
this contract; direct LSU latency alone cannot validate earlier scheduling.

The possible cost is a longer wakeup-tag/control path into the IQ. Do not enable
this option by default without its own routed timing comparison.

Status: candidate, disabled by default until correctness and timing gates pass.
Baseline: 4363ae59, eight LSU-acceptance-to-completion cycles for warm aligned
loads with resident nonidentity DTLB mappings; initiation interval one cycle.

The first experiment removes the descriptor ring's mandatory enqueue-to-send
cycle when it contains no unsent commands. P4 retains every existing forwarding,
dependency, serialization and admission check. A cacheable single-fragment load
which enqueues can also present its cache command on that edge. The response
descriptor is still allocated; only its initial sent state changes on a real
cache handshake. A blocked command remains in the ordinary ring for retry.

No bypass is allowed with an older unsent command, a full ring, split access,
inhibited access, flush, or lost LSU ownership. Responses retain FIFO association.
The D-cache response remains registered, so the descriptor exists before any
response can arrive. Forwarded loads do not enqueue and cannot issue a cache read.

Correctness gates must exercise early consumption, dependent loads, backpressure,
SQ forwarding, split/fault paths, flush and walker handoff. Compare the same core
configuration with the option off/on for latency and timing. Do not infer routed
Fmax or whole-system IPC improvement from a directed simulation result. Keep the
existing registered path selectable until those comparisons pass.

## Initial verification

The four changed-VPN A/B cases (hint control off/on, fall-through off/on) pass:
all eight independent resident loads use the early result, at II=1. Latency is
eight cycles with fall-through off and seven with it on. A 16-load dependent
pointer chase through the real integer PRF gives the same eight-versus-seven
LSU latency; this fixture does not include IQ wakeup/selection overhead.

With fall-through enabled, all ten split-ring tests and fourteen fast/precise
tests pass, including descriptor saturation, flush poisoning, inhibited accesses,
faulting split halves and reordered/chaotic memory responses. Board IPC and
routed timing remain unverified for this candidate.

The matched timing experiment is `bash synth/run_ls_latency_gate.sh <commit>`.
It creates fresh baseline/candidate worktrees at the same commit, generates the
same `M68kFullCoreSynth` configuration with only `--aligned-load-fall-through`
differing, and runs the existing 200 MHz post-route recipe serially under the
Vivado mutex. Logs, netlist hashes and checkpoints remain in the printed temporary
directory. This is a core OOC screen, not SoC board timing signoff.

## Full-core simulation comparison

`LsFallThroughIpcSpec` runs the real frontend, rename, IQ, LSU and ROB with the
option off/on, seeds 1 and 17, transparent cacheable mappings and the default
ideal-memory model. Its pointer chase alternates two explicitly initialized
nonzero pointers, checking every accepted data address. A harmless branch/NOP
guard prevents speculative execution into randomized memory beyond the program
image from contaminating the measurement. The windows below include setup and
cache warm-up, from first through last counted retirement; these are not board
measurements or isolated steady-state-only windows.

| Kernel | Seed | Retired | Baseline cycles | Candidate cycles | IPC gain |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dependent pointer chase | 1 | 388 | 2889 | 2626 | 10.02% |
| Dependent pointer chase | 17 | 388 | 2887 | 2631 | 9.73% |
| Independent loads | 1 | 481 | 689 | 638 | 7.99% |
| Independent loads | 17 | 481 | 693 | 642 | 7.94% |
| Same-line disjoint store/load | 1 | 244 | 303 | 292 | 3.77% |
| Same-line disjoint store/load | 17 | 244 | 307 | 296 | 3.72% |

For both seeds, 253 dependent command-to-command gaps are exactly 11 cycles in
the baseline and 10 in the candidate. The remaining gaps include initial misses
and branch recovery. This corroborates that the one-cycle LSU saving survives
the IQ/wakeup/retirement machinery. It does not establish a Dhrystone gain or
preserved Fmax; the matched timing run remains a separate required gate.

## Combined wakeup and fall-through experiment

The same full-core test now compares all four combinations of the two options.
With both enabled, the measured retirement windows are:

| Kernel | Seed | Retired | Baseline cycles | Combined cycles | IPC gain |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dependent pointer chase | 1 | 388 | 2889 | 2369 | 21.95% |
| Dependent pointer chase | 17 | 388 | 2887 | 2374 | 21.61% |
| Independent loads | 1 | 481 | 689 | 586 | 17.58% |
| Independent loads | 17 | 481 | 693 | 590 | 17.46% |
| Same-line disjoint store/load | 1 | 244 | 303 | 278 | 8.99% |
| Same-line disjoint store/load | 17 | 244 | 307 | 282 | 8.87% |

For each seed, 253 dependent command gaps fall from 11 to 9 cycles. Earlier
wakeup alone gives 2625/2630 pointer-chase cycles; fall-through alone gives
2626/2631. All 24 kernel/seed/mode simulations pass, including the next-cycle
writeback assertion. This remains microbenchmark evidence, not board performance
or routed timing signoff. Both options remain disabled by default.

Eight selected `ExecuteLockStepSpec` cases also pass with both
`LOCKSTEP_LS_FALLTHROUGH=1` and `LOCKSTEP_LS_EARLY_WAKEUP=1`: integer MOVE/MOVEA,
partial-overlap forwarding, cross-line load/store, LEA, PEA, interrupt/RTE
recovery, and long inhibited-store bursts with normal and slow memory.
The 24 split-ring and fast/precise LSU regression cases also pass with the
announcement/writeback assertion active (their wakeup output remains registered).
The required `make SBT=/home/qwertyoruiop/sbt/bin/sbt test-fast` gate passes:
381 succeeded, zero failed, two ignored.

The original matched run's baseline at `ab849ea7` completed core OOC routing:
setup WNS +0.011 ns, hold WHS +0.023 ns, pulse-width slack +1.958 ns, with zero
failing endpoints in all three categories. Its candidate arm is still pending;
this baseline result says nothing yet about the optimizations' timing impact.

The timing launcher accepts `LS_LATENCY_MODES="baseline combined"` for a matched
comparison including `--early-ls-int-wakeup`; `earlywake` and `fallthrough` are
also available as individual arms.

## Broader correctness and performance coverage

An additional 25 selected lock-step cases pass with both options enabled,
covering MOVEM addressing/unaligned accesses, page crossings and partial faults,
page-fault and write-protect recovery, partial-register loads and DIVREM across
mispredictions. These complement, rather than replace, the earlier eight cases.

The IPC comparison now includes ten kernels, two seeds and all four option
combinations. Broader coverage exposed a measurement defect: the histogram
excluded temporary loads but failed to honor the oracle's drop/keep markers,
counting BSR's internal stack push as an additional instruction. It now derives
each cycle's macro count directly from `WhiteboxCapture.emitted` and ends at the
requested instruction boundary, excluding later guard/drain retirements. Every
run asserts the exact macro count, and every A/B comparison requires equal counts.

All 80 ideal-memory simulations pass. The original three load-focused results
above are unchanged by the counter fix. Combined-option call/return gains are
11.41–12.53% over 805 architectural instructions; dependent/independent ALU,
hot-loop, same-address load/store and mixed kernels are unchanged. Store-stream
improvement is only about 0.21%. This is evidence that the load optimizations do
not solve every IPC bottleneck; store dependency tracking remains separate work.

All 80 simulations also pass with `IPC_MEM=l2:5:70` (modeled 5-cycle L2 hits,
70-cycle DDR, applied to both instruction and data memory). Combined-option
gains across seeds 1 and 17 are:

| Kernel | IPC gain with modeled L2/DDR |
| --- | ---: |
| Dependent pointer chase | 21.10–21.34% |
| Independent loads | 15.85–15.92% |
| Same-line disjoint store/load | 7.46–8.38% |
| Call/return | 8.27–10.40% |
| Store stream | 0.16% |
| ALU, hot loop, same-address load/store, mixed | 0% |

These are short full-core windows including warm-up, not system-level Dhrystone
results. No default RTL setting changed in this expanded measurement pass.
The required fast gate was rerun after the histogram fix and corpus expansion:
381 passed, zero failed, two ignored.

## Diagnosing remaining store-side stalls

The comparison additionally includes copyback variants of `load/store` and
`mixed`, plus a directed delayed-store-data kernel. The latter warms two distinct
lines, computes store data with DIVU.W, stores to `0x4600`, and loads from
`0x4610`. The address ranges cannot overlap; division delays the store's data,
not its address. This is a baseline for future memory-order integration, not a
claim that letting this load pass will remove the divider throughput limit.

The simulation-only `[ls-order-window]` diagnostic uses the exact same first-to-
requested-last macro window as IPC. It counts overlapping predicates: oldest LS
entry unready, oldest entry an unready store, any younger ready load behind that
entry, and LS skid occupancy. These describe speculative IQ state, not mutually
exclusive stall attribution or physical-address disambiguation. No production
counters or scheduling logic are added.

In the ideal-memory baseline, seed 17's delayed-store kernel spends 2,084 of
2,163 cycles with an unready oldest store and a younger ready load. All 32 stores
hit L1D, with zero drain-backpressure cycles. Conversely, the copyback load/store
and mixed baselines have no oldest-unready cycles: their 290/326 instructions
take 345–350/332 cycles. The MMU-off variants' much lower IPC must not be
mistaken for copyback-store performance on the board.

All 208 runs (13 kernels × two seeds × four option modes × two memory models)
pass. With both optimizations, ideal-memory copyback load/store improves
5.18–5.42% and mixed-copyback improves 6.07–6.75%; with modeled L2/DDR those gains
are 4.24–4.49% and 4.85%, respectively. The divider-bound directed kernel gains
only about 0.09%, as expected while strict memory ordering remains unchanged.
The fast gate passes after adding these diagnostics and kernels: 381 passed,
zero failed, two ignored.

## Timing investigation: descriptor fall-through

The `ab849ea7` fall-through arm reports -1.252 ns at post-route round zero
and -1.175 ns after round one;
subsequent physical optimization is still running. This is not an acceptable
200 MHz result. Its synthesis timing report identifies a 25-level path from
`RobPlugin_logic_head_reg[2]` to the D-cache dirty-memory read register, through
`alignedFallThrough`, load-address selection and early-probe matching. The
fall-through permission currently selects payload bits as well as gating valid,
putting late store-age/barrier logic ahead of address-dependent cache work.

A follow-up should separate payload selection (registered ring pointer/occupancy
state) from transaction permission, retaining all valid-side checks. That is a
candidate timing repair, not yet implemented or proven. The queued matched run
now includes baseline, early-wakeup-only and combined configurations so the
wakeup optimization can be evaluated independently of the fall-through risk.
