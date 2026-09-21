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
3. **Debug readback:** registered per-word decode rather
   than broadcasting binary read-address bits through wide read muxes.
   Preserve the address map, snapshot point, reset domains, AXI backpressure,
   and existing live/history/PRF paths. Candidate implemented; physical
   benefit remains to be measured separately from the running IQ/L2 build.
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

## Integrated candidate queued

CPU cleanup commit `ef243e3ed4230ff488bc04b884272440c424dc41`;
SoC L2 change `c4b3a25`, combined pin
`800e224757f5d7bd13f279054531f66d33464e38`. Full 200 MHz build in the isolated
`ipc-v2-l2-cleanup` worktree, service `m68k-ipc-cleanup-soc200.service`,
runner `/tmp/run-ipc-cleanup-soc200.sh`. Same reduced-debug throughput-v2
configuration and real MIG; route directive Explore matches the successful
recovery. No timing exceptions, reset changes, capacity reductions, or extra
pipeline stages. The runner verifies matched cycle/IPC logs before generation,
then runs both top-level lint modes and existing source/profile/reset guards.

Logs `/tmp/ipc-cleanup-soc200-{build,generation,lint}.log` are followed in the
existing `build-logs` tmux pane. Implementation is mutex-serialized and does
not program the board. Check service/log/artifact state before claiming
completion or timing benefit. Previous core-only optimization gates remain
deferred. Continue investigating debug-readback pressure while this runs.

## Debug CSR word-decode candidate

Capture each implemented static CSR word's select on the existing AR handshake.
Keep the binary address for history bodies and live integer PRF addressing;
do not change writes, CPU pipeline logic, retirement, or any external service.
Each region now combines masked words with a balanced OR tree. Only address
decode moves: source values are still sampled at `doRead`, then the same six
partial-word registers feed the existing response stage. Full 20-bit address
comparisons preserve unaligned/unmapped reads returning zero. An elaboration
check rejects duplicate offsets across regions.

This trades small per-word selector registers for narrower, local data-select
fanout. It is a physical experiment, not an assumption that more FFs or one-hot
encoding always route better. Measure total LUT/FF cost, residual AR/ARREADY
fanout, and integrated congestion/timing before accepting it for deployment.

Focused candidate gate: 21 tests passed (`/tmp/ipc-cleanup-debug-focused-r2.log`),
covering the seeded full-map readback, AXI, reset, and two new directed tests.
The latter assert the exact original two-stage response timing and deliberately
change the live PC between AR acceptance, stage-1 sampling, and stage-2 response;
they also hold a second request while backpressuring the first, check upper/lower
address aliases, and assert CPU reset immediately after AR acceptance.
The first compile attempt failed due to the missing balanced-tree extension
import; corrected before simulation. Full fast gate: 390 passed, 2 ignored,
zero failures (`/tmp/ipc-cleanup-debug-fast.log`). The same new tests
against baseline `ef243e3e` also pass 2/2 (`/tmp/ipc-cleanup-debug-baseline.log`),
so the cycle/sampling assertions describe the old behavior too. Production
throughput-v2 socket generation with detailed counters passes, as does the
socket-specific structural port checker (not the unrelated fullcore-port check).
Generated artifact: `generated/csr-decode/M68kSocketTop.v`;
generation log: `/tmp/ipc-cleanup-debug-generation.log`.
Standalone production-socket Verilator lint passes with `-Wno-fatal`; both
baseline and candidate report the same 144 warning headers after normalizing
paths/line numbers. This is not a warning-free or full-SoC lint claim. Logs:
`/tmp/ipc-cleanup-debug-socket-lint{,-baseline}.log`. Generated Verilog SHA256:
`ec3010141c2e9e02243086960f682073acc15bdf1adf79d5b556c8c7faac9d7c`.

IPC status: no new board measurement. The earlier board-copy cycle match applies
to the IQ change, not this debug change: that benchmark uses `FullCoreDut`, which
does not instantiate `DebugCtrlPlugin`. Do not label rerunning it as coverage
of this candidate. The debug rewrite has no CPU execution-path changes; its
physical impact and any board-level effect remain unverified.

## Integrated IQ/L2 synthesis result (not placement/routing)

The first combined cleanup SoC has completed synthesis with zero errors and
zero critical warnings. Compare the same synthesized hierarchical report
against the original lean-200 build, not against routed or isolated counts:

| Hierarchy | Original total LUTs | IQ/L2 candidate total LUTs | Delta |
|---|---:|---:|---:|
| Whole SoC | 157370 | 157545 | +175 |
| CPU socket | 99483 | 100326 | +843 |
| L2 | 10311 | 9637 | -674 |
| L2 MSHR (included in L2) | 6670 | 5828 | -842 |

Whole-SoC FFs are 93287 -> 93292; BRAM/URAM/DSP totals are unchanged.
The local L2 saving survives integration, but a CPU-area increase offsets it.
Do not claim this combined candidate is an area reduction. The RTL change in
the CPU is the removed redundant IQ trigger-mask flush; altered synthesis
mapping/replication still needs attribution. Keep the run intact to measure
actual routing and timing: LUT totals alone do not decide the fanout tradeoff.
The debug word-decode candidate is NOT included in these counts.

Reports: `build/vivado/reports/utilization_synth.rpt` in the
`ipc-v2-200mhz-lean` and `ipc-v2-l2-cleanup` SoC worktrees.

## Integrated CSR candidate queued

CPU `1cb2401f6b77c8e8b55fd5593e405b75d6596875`; SoC
`8be2850a99968071b5aac9bd114c89afa0a6743a`, branch
`perf/csr-read-decode-cleanup`, worktree `ipc-v2-csr-cleanup`.
The sole SoC change from the running IQ/L2 build is the CPU submodule pin.
The CPU adds the tested static CSR word-decode change, with no execution-path
or performance-profile changes. Both candidates retain ETH, detailed counters,
VIO and JTAG AXI; IPC/legacy ILAs and storage trace remain off.

Service `m68k-ipc-csr-cleanup-soc200.service`, runner
`/tmp/run-ipc-csr-cleanup-soc200.sh`, logs
`/tmp/ipc-csr-cleanup-soc200-{build,generation,lint}.log`.
Production generation, both SoC lint modes, post-route early-exit tests,
IPC-profile guards, storage reset pairing, and synthesis-source checks passed.
This CSR-only runner reached the Vivado mutex, then was deliberately stopped
while its sole child was still `flock`, before any Vivado launch. The replacement
CSR+PRAM candidate below carries the same CPU pin and adds the existing, tested
PRAM BRAM implementation. Preserve this worktree and its lint/generation logs
for a separate CSR-only comparison if needed. No programming/reset operation
is included in either runner.

Before the CSR implementation begins, the same serialized runner requests a
read-only primitive census of the original and IQ/L2 synthesized checkpoints.
Outputs: `/tmp/ipc-cleanup-cell-{baseline,iq_l2}.tsv`, matching `.log` files;
script `/tmp/ipc-cleanup-cell-census.tcl`. It groups primitive types by surviving
plugin-name prefixes and SoC hierarchy, explicitly retaining unattributed CPU
cells. This is heuristic ownership, not a causal attribution or a count of
physically packed LUT sites. A Tcl smoke test covers grouping/aggregation;
the actual Vivado census has not run yet. Its failure is logged independently
and does not prevent the already-gated CSR implementation from starting.

Next physical decision: compare routed WNS/TNS/failing endpoints, hold, routing
resource footprints and congestion against the loaded baseline. Do not select
the IQ cleanup on its RTL simplicity alone, or the CSR change on its tests
alone. If the current IQ/L2 implementation worsens routing, preserve its report
and test a variant retaining the L2/CSR savings without the IQ flush change.

## Recover the previously unintegrated PRAM BRAM cleanup

The active IQ/L2 build and original CSR-only queue still used flop-based PRAM.
Existing SoC commit `a689a42f0cee2e289b17bee1d015cf0696cce982` on
`perf/pram-bram` had already implemented the user's accepted 256-byte clear
sweep, with synchronous dual-port RAM and restore backpressure. Its earlier
matched synthesis completed, although its documentation still said queued.
The unchanged RTC source compares as 4058 -> 205 LUTs and 2208 -> 146 FFs,
with one READ_FIRST RAMB18. These are matched OOC figures; the integrated
flop-based RTC is 1705 LUTs/2210 FFs and needs its own after measurement.

Cherry-picked that implementation into a separate SoC worktree:
`ipc-v2-csr-pram-cleanup`, branch `perf/csr-pram-routing-cleanup`, pin
`616d8fa682e9eb95f59882608754260a86d3fdef`. CPU remains
`1cb2401f6b77c8e8b55fd5593e405b75d6596875`; no new CPU RTL or profile change.
Fresh `make tb-rtc tb-pram-bram-cdc tb-pram-sd tb-pram-sd-populated
tb-pram-sd-autoload` passes: 21 RTC cases, both CDC ratios/phases, 14 persistence
cases per default image and two autoload cases. Log:
`/tmp/ipc-cleanup-pram-integration-tests.log`. Tests cover all bytes, read/write
collisions, reset during clear, restore during clear, and restore during reset.

Replacement service: `m68k-ipc-csr-pram-cleanup-soc200-r2.service`;
runner `/tmp/run-ipc-csr-pram-cleanup-soc200-r2.sh`; logs
`/tmp/ipc-csr-pram-cleanup-soc200-r2-{build,generation,lint}.log`.
It performs fresh production generation and both SoC lint modes before waiting
behind the unchanged IQ/L2 implementation. It retains the read-only cell census
ahead of implementation, the same 200/50 MHz clocks, ETH/counters/reset controls,
and disabled ILA/trace settings. The active build was not interrupted; the
CSR-only queued service is intentionally inactive, not crashed. The build-log
tmux pane follows the active and replacement build streams.

The first replacement runner had a script-generation error: JavaScript string
replacement interpreted a shell-regex dollar/apostrophe sequence as replacement
syntax and duplicated script text. It was stopped while waiting on the mutex,
before Vivado launched; its missing-SBT/generation errors are not RTL failures.
The corrected r2 runner uses literal replacement, exact-string test receipts,
and explicit throughput-v2/200/50 MHz profile assertions. Original error logs
remain under the unsuffixed name. Do not confuse them with the r2 build.
The r2 generation, both SoC lint modes and all profile/reset/source checks now
pass; the live r2 service is waiting on the Vivado mutex. This is a verified
queue wait, not a second concurrent implementation.

No new IPC result is claimed: this adds only PRAM clear/read-interface latency,
not CPU pipeline latency, and preserves the tested CPU candidate. Require the
integrated BRAM inference and routing result before accepting physical benefit.
No board reset, programming, flash, or storage write has been performed.

## IQ/L2 placement checkpoint

The active IQ/L2 candidate completed placement and entered pre-route physical
optimization. At the same `timing_place.rpt` stage, original lean-200 versus
IQ/L2 is WNS -1.846 -> -0.509 ns, TNS -2226.868 -> -374.695 ns, setup failing
endpoints 6856 -> 2617. Hold remains negative before routing (-0.396 -> -0.416
ns); pulse-width slack is zero in both. These are placement estimates, NOT the
original routed -0.249 ns result and NOT proof of a routed improvement.
Keep both candidates and wait for the actual routed reports before deciding
whether the extra synthesized CPU LUTs are worthwhile.

## Expanded routed-resource attribution

`synth/routing_pressure.tcl` exports `routing_pressure::run <route.dcp> <out>`
for read-only use under the existing Vivado mutex. It scans all canonical nets,
not only the highest-fanout nets, and writes `nets.tsv`, `drivers.tsv`,
`owners.tsv`, `families.tsv`, and `summary.txt`. Metrics are load count,
routed PIPs/nodes, and per-net tile footprint. Driver and full net names remain
available for manual review. Bus families normalize numeric bracket indices
only; synthesized LUT instance numbers are not collapsed into fictitious buses.

Global-buffer, constant, external and multiple-driver nets are separate classes.
Ownership uses the last surviving plugin prefix rather than the first containing
hierarchy. This matters because synthesis moves, for example, D-cache cells
under `LsEuPlugin_logic_sq` and debug cells under PRF RAM hierarchy. Both naming
and bus grouping remain heuristics, not proof of logical ownership or causation.

Net aliases are canonicalized across hierarchy. The sum of per-net PIP counts
must equal the unique PIPs queried across all net segments; a mismatch aborts
the ranking rather than silently counting a tree twice or omitting its child
segments. Zero routed PIPs are also an error. These counts measure resource
footprints, not physical wire length, local available routing capacity, or
which net caused another net's timing failure. Never sum per-net tile counts
as if they were distinct occupied tiles for a module.

`tools/test_routing_pressure.tcl` passes mocked tests for driver ownership,
hierarchy alias deduplication, global classification, multi-driver preservation,
wide-bus grouping, reconciliation failures and unrouted-design rejection.
That is Tcl/unit evidence only, not actual Vivado API validation. The serialized
cell-census hook will run the real baseline and IQ/L2 routed scans before the
next SoC implementation, using the completed recovery checkpoint for the original
baseline. Output directories: `/tmp/ipc-cleanup-cell-{baseline,iq_l2}-routing`.
Missing routed checkpoints and failed scans are reported explicitly; they do not
prevent the already-gated CSR+PRAM build from starting. Actual scan results are
still pending. A default five-minute scan budget bounds the diagnostic delay;
timeout leaves an explicitly incomplete raw TSV and no completed ranking. A
fresh output directory is required to prevent stale-summary reuse. No RTL,
clocks or timing exceptions changed for this tooling.
Repository fast gate after tooling changes: 390 passed, 2 ignored, zero
failures (`/tmp/ipc-cleanup-routing-tools-fast.log`). The active IQ/L2 run has
entered `route_design -directive Explore`; router-initial timing is not a
completed routing result and must not be reported as closure.

## IQ/L2 route crash and shared-constant census correction

The IQ/L2 `Explore` route terminated with SIGSEGV at Phase 11.1.1
`Leaf ClockOpt Init`, after Phase 9 route verification succeeded. The service
is terminal (`ExecMainStatus=2`, underlying make error 139), not stalled.
Only synthesis and placement checkpoints survived. Its last intermediate
timing was WNS -0.335 ns, TNS -82.911 ns, WHS +0.010 ns: these are NOT a
final routed result and cannot establish improvement or closure. MIG
`Route 35-4578` warnings also occur in the loaded-baseline recovery log
(10 occurrences versus 5 in this crashed run); they are not newly introduced.

CSR+PRAM retains implementation priority and acquired the shared mutex.
A separate `m68k-ipc-cleanup-soc200-recover-aggressive.service` is queued behind
it. `/tmp/run-ipc-cleanup-soc200-recover-aggressive.sh` verifies the IQ/L2
source pin plus flow/placement SHA256s, then restores placement, replays the
original pre-route optimization and tries `AggressiveExplore`. Outputs go to
`ipc-v2-l2-cleanup/build/vivado-recover-aggressive`, preserving the original
run. There is no RTL, clock or timing-exception change. The recovery produces
checkpoints/reports only; it does not emit/program a bitstream. This is a
netlist-dependent workaround attempt, not a proven fix for ClockOpt crashes.
The reused `build-logs:0.0` pane follows CSR+PRAM and recovery logs.

The first real baseline routing census exposed physical constant aliases:
separate GND nets each returned the same 113745-PIP/119768-node tree. Logical
net-name canonicalization alone therefore does not make constant trees unique.
Its partial per-net output is retained, but its totals must not be used.
The scanner now unions all constant-net PIPs once and reports that shared cost
in the summary; constant nets have explicit `NA` footprints and are excluded
from per-driver/owner/family rankings. Nonconstant nets retain normal attribution.
The full-design PIP reconciliation remains mandatory. A new mocked alias
regression passes. One bounded baseline retry is arranged before CSR+PRAM
implementation, in `/tmp/ipc-cleanup-cell-baseline-routing-r2`; the original
scan and retry each keep the five-minute scan budget. Actual completion and
the reconciliation result are still pending.

The completed synthesis primitive censuses attribute the mapping spread as
follows (LUT1..LUT6 primitive objects after opening the synthesis DCP, NOT
physical packed LUT sites): IQ 7790 -> 7619 (-171); decode 21641 -> 22588
(+947); ROB 9066 -> 9382 (+316); D-cache 7022 -> 7261 (+239); FPU/Div EU
13634 -> 13343 (-291); L2 MSHR 6676 -> 5588 (-1088). These use name-based
ownership and must not be mixed with `report_utilization` packed-LUT counts.
Rechecked `git diff ca3f31da ef243e3e -- src/main`: the IQ flush assignment
removal is the ONLY CPU RTL change. Thus the CPU-wide increase is not confined
to the edited IQ; netlist-wide mapping/optimization changes need to be judged
by routed results, not by blaming the changed module's local LUT count.

First real scan terminated at its 300-second budget after 30117 nets. The
constant-corrected retry is live but also slower than a whole-design scan
within five minutes. A separate read-only service,
`m68k-ipc-cleanup-baseline-routing-full.service`, waits for both CSR+PRAM and
IQ/L2 recovery to terminate before acquiring the same Vivado mutex. It skips
itself if the bounded retry already produced a reconciled summary; otherwise
it scans the loaded baseline with a 1800-second loop budget into the fresh
`/tmp/ipc-cleanup-cell-baseline-routing-full` directory. This preserves build
priority and makes the promised complete census an actual queued task, not
an interpretation of partial totals. Log:
`/tmp/ipc-cleanup-baseline-routing-full.log`. No board access is involved.
After the constant-alias correction the required repository fast gate passed:
390 tests, 2 ignored, zero failures, in
`/tmp/ipc-cleanup-routing-constants-fast.log`. The Tcl alias regression and
cell-census smoke test also passed. Full real-design PIP reconciliation is
still unverified until a scan completes.
