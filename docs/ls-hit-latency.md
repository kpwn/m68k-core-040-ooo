# Resident-load latency experiment

## Socket diagnostic profile

`CPU_IPC_PROFILE=throughput-v1` explicitly selects the measured load descriptor,
integer/flag wakeup, direct long MOVE, early store address, SQ reservation,
publication/subword forwarding and four-context detached-store options in the
real socket. It also enables selective slot-1 training/deferral and retained
redirect history. Ordinary retirement stays two-wide. `baseline` remains the
default; unknown names fail elaboration. Both retain the socket's existing
byte order, reset/AXI response ownership, translation and peripheral ordering.
The SoC build metadata must name the profile and pinned CPU revision. First board
comparison is a 100 MHz diagnostic with detailed counters/ILA, not 200 MHz
acceptance. Combined correctness, socket checks and integrated timing are gates.

`CPU_IPC_PROFILE=throughput-v2` adds the validated postincrement late-store
reservation and wake-qualified data capture experiments to v1. Both IQ and LSU
enable the matching postincrement contract; only LSU opts into earlier data
qualification. V1 remains unchanged for repeatable comparisons. The v2 SoC
candidate targets 200 MHz directly at the user's request, with the same Ethernet,
debug and performance features; selecting the profile does not imply timing
closure. The current CPU revision also contains the precise IRQ macro-boundary
fix. No new external socket signals or architectural ordering changes.

## Queued late-store completion owners (experiment)

Keep the existing single detached owner as the default. An optional bounded FIFO
may retain additional translated, cacheable, ordinary store completion contexts
behind it. Only SQ slot/ROB identity, source tag, size and completion flags are
queued; the SQ remains the sole address, byte-mask and data owner. Address issue
and SQ allocation stay ordered. Unknown addresses, unfilled overlapping stores,
inhibited accesses and unsupported store forms retain their existing barriers.

The oldest context alone uses the existing readiness query and PRF read port.
Every head replacement clears the registered readiness qualification and captured
NZVC state. Publication still precedes completion and back responses retain
completion priority. An admission must have both an SQ slot and context capacity;
do not depend on same-cycle completion credit. New admissions may not bypass a
queued context, including while the FIFO read is pending. Reset/flush cancel all
contexts together with their uncommitted SQ reservations. No external producer
retains a context after cancellation. Store sources remain live until completion.

Empty-tail turnover experiment: an already-admissible context may replace the
active owner on its completion edge when the tail occupancy is zero. Admission
capacity must remain independent of completion: if completion loses arbitration,
the same admitted context fits in the tail instead. Select the incoming payload
using registered tail emptiness, not the completion signal. A replacement still
clears readiness qualification; it must never capture the new source using the
previous owner's readiness. Compare against the pinned queued-context baseline
before retaining this bypass.

Compare one, two and four owners with matched full-core IPC. Require actual
multiple-owner occupancy and disjoint-load progress, plus full-capacity, alias,
flush, source-reuse and completion-contention correctness coverage. Reject added
machinery if it produces no useful throughput gain. This is a bounded extension
of known-address bypass, not generic out-of-order address issue or a memory-order
replay implementation. Physical acceptance remains a separate 200 MHz gate.

## Guaranteed next-cycle NZVC wakeup candidate

An independent default-off option applies the selected-completion guarantee to
LSU-produced NZVC. Announce the physical flag destination only when the successful
front, back, detached-store or precise-replay completion has won arbitration and
will write that destination in the next cycle. Preserve the existing registered
NZVC value/writeback, ROB completion and architectural CCR paths. Do not predict
store data arrival, memory response success or future completion-port availability.

IQ dynamic NZVC dependencies clear into registers before registered issue and
operand capture; a newly pushed consumer must use the same announcement. Fresh
physical-tag allocation retains priority over a wake for the prior lifetime.
Faults and orphan replays announce nothing; ordinary IQ flush squashes consumers.
Cycle-by-cycle assertions require exact next-cycle NZVC write eligibility and tag
matching, including back-to-back completions. X and FPCC timing is unchanged.

Measure store→Scc, load→Scc and load→branch recurrences against the same core with
the option disabled, plus the existing broad LSU and branch-profile controls.
Verify NZVC values/partial registers, aliases, precise stores, split/fault/flush,
IRQ and RTE against the oracle. A latency benefit is not branch accuracy or board
IPC. Retain only with useful measured IPC and pursue a separate routed 200 MHz
comparison; adding tag/control fanout to the IQ remains a timing risk.

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
failing endpoints in all three categories. The original fall-through candidate
later failed setup; the repaired `b99636f4` combined candidate subsequently passed
at +0.002 ns setup / +0.021 ns hold, with 1,082 fewer LUTs than baseline and
unchanged measured IPC versus its pre-repair implementation. Early wake alone
passed at +0.030 ns setup. These are core OOC results, not full-SoC signoff;
see the [experiment ledger](ipc-experiments.md) for matched reports and counts.

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

The `ab849ea7` fall-through arm finished at -1.160 ns after three post-route
rounds (3,275 failing setup endpoints, TNS -1196.957 ns). Hold and pulse width
pass at +0.019/+1.958 ns. This is not an acceptable 200 MHz result.
The routed worst path starts at P4's ROB-id register and ends at the D-cache
dirty-memory read register. Its synthesis timing report identifies a 25-level path from
`RobPlugin_logic_head_reg[2]` to the D-cache dirty-memory read register, through
`alignedFallThrough`, load-address selection and early-probe matching. The
fall-through permission currently selects payload bits as well as gating valid,
putting late store-age/barrier logic ahead of address-dependent cache work.

The follow-up separates payload selection (registered ring pointer/occupancy
state) from transaction permission, retaining all valid-side checks. This is
implemented as an unproven timing repair. Its matched timing run must include
baseline, early-wakeup-only and repaired-combined configurations.

### Payload-selection repair contract

When fall-through is enabled, select P4's command payload whenever the send and
push pointers coincide and the descriptor ring is not full. Those conditions
depend only on registered ring state. They imply that no queued unsent command
can be selected at the same time; simulation must assert this invariant.
Transaction valid retains the original enqueue, cacheability, forwarding,
barrier, ownership and flush qualifications. Selecting an invalid P4 payload
does not authorize a read. Every valid command must therefore have exactly the
same payload, token and handshake cycle as before this repair. Responses and
descriptor allocation are unchanged. This does not assert that the remaining
valid-side path will meet timing; it removes only the late payload-mux control.

An initial regression exposed an invalid fixture encoding: `issueLea` used
`MemOp.LOAD` with `leaAddr=true`, whereas the decoder uses `MemOp.NONE`.
The malformed train launched probes while completing as LEA, eventually leaving
duplicate probe tokens. The test now uses the real encoding and asserts no
translation fires during its LEA-only phase. Its original mandatory precise-
replay collision and exactly-once completion assertions remain intact and pass.

The payload repair passes all 28 focused LSU cases, 29 selected oracle cases and
the 381-test fast gate. All 104 L2/DDR-model benchmark rows have exactly the same
retired counts and cycle counts before and after the repair. The matched core-only
route subsequently passed 200 MHz (combined +2 ps setup); integrated-SoC timing
and board validation remain required.
