# Resident-load latency experiment

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
faulting split halves and reordered/chaotic memory responses. Full-system IPC and
routed timing remain unmeasured for this candidate.

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
