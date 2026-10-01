# Performance candidate acceptance

This is the acceptance record for `integ/nax-performance`, not a release verdict.
The objective remains a production-grade 68040 core with higher useful throughput
and IPC without disproportionate FPGA area or timing cost. Feature defaults stay
unchanged until their applicable gates pass.

## Candidate and measured mechanisms

The candidate starts from `codex/write-integration` at `5577d139` (including its
D4 and shipping-harness prerequisites), then integrates the BoardMembench trace
and direct-refill-response changes through `ed0872f3`. The cache constructor,
response mux, and fault mux are explicitly reconciled so a nonblocking response
retains its data, identity, and fault while the legacy direct-refill path remains
available. These are separate paths; their measured gains must not be added.

| Mechanism | Evidence before integration | Scope / limitation |
| --- | --- | --- |
| Direct legacy refill response | Modeled L2-served chase 21.009 -> 18.024 cycles/hop; L1 unchanged at 9.000 | Direct cache/memory-model connection, not SoC or board timing; default OFF |
| Four nonblocking miss entries | Checked four-chain workload 0.3651 -> 0.6552 read B/cycle versus legacy, same modeled workload and LS-OoO setting | Independent-chain workload; not an aggregate IPC claim |
| Eight-entry load ring | Sequential stream 0.9352 -> 1.0600 read B/cycle versus nonblocking ring4, seed 1 | Distinct-line MLP increases; ring16 did not increase maximum occupancy beyond 8 on this workload |
| Asynchronous dirty eviction | Continuous copy/grouped/MOVE16 streams have thousands of refill ARs before an older victim B | Overlap is proven; scalar-copy throughput improvement is not |
| Nonblocking data integrity | Eight-seed cache stress including reordered responses, delayed write visibility, mixed WT/CB; store-queue control and five mutants | Further cache-specific mutation and saturation gates remain mandatory |

Source logs are `/tmp/codex-bw-mlp-{legacy-r4,n2-r4,n4-r4,n4-r8,n4-r16}.log`,
`/tmp/codex-bw-overlap.log`, `/tmp/codex-bw-nbstress3.log`,
`/tmp/codex-bw-sqstress-full.log`, and the agent-46 chase logs. The upstream
individual commits have fast-suite passes; those do not certify this merged tree
or the flag-ON paths.

## Gates before enabling or shipping

1. **Merged-tree behavior.** Run `make SBT=~/sbt/bin/sbt test-fast` and focused
   direct-refill and nonblocking cache tests on this exact candidate. Exercise
   both features together explicitly; default-OFF fast tests alone are
   insufficient. Check response identity, fault delivery, and exactly-once
   completion under contention.
2. **Miss/writeback pressure.** Force all writeback entries full, prove a fifth
   dirty miss waits without overwriting state, then prove progress with checked
   data when B is released. Separate WB occupancy from demand stalls. Require
   independent AW/W backpressure and stable AXI VALID/payload until acceptance.
3. **Mutation coverage.** Demonstrate each of the ten nonblocking-cache mutants
   listed in the approved D-side spec fails on a behavioral assertion or checked
   result, with the clean version passing. Compile failures do not count.
4. **Load concurrency.** Keep independent-chain, sequential-stream, grouped-copy,
   and dependent-chase controls. Record ring occupancy, distinct-line MLP, AXI
   outstanding IDs, useful bytes/cycle, and checked results for ring4/8/16 and
   MSHR2/4. Label the legacy blocking baseline separately.
5. **Architectural correctness.** Compare shipping corpus and lockstep failure
   *names* on matched OFF/ON source/configuration. Existing FPU, exception, and
   MMU failures are production defects to resolve, not a passing production
   baseline. Integrate CPUSH and targeted DTLB fixes only with their own evidence
   and combined-feature coverage; do not infer gate transfer across branches.
6. **SoC integration.** Generate actual socket RTL with the read hot door OFF/ON;
   lint and test CPU/SoC port agreement, address routing, errors, and inhibited
   ordering. The current CPU hot door is read-only. A tested standalone SoC
   write door does not mean the CPU write path uses it.
7. **Area and timing.** Compare identical configurations with only the candidate
   lever changed. Raw register/storage counts are provisional and must not be
   reported as routed LUT/FF cost. Ring growth duplicates context and parked
   response state, not just pointers. Preserve the measured 200 MHz control-path
   constraints and evaluate paired implementation reports; slack alone is not a
   boot verdict.
8. **Silicon validation.** Owner-coordinated board tests are still required for
   representative workload IPC, L1/L2/DDR chase, dirty copy, boot/fault behavior,
   and sustainable throughput. No automatic board access is authorized here.

## Current ownership

- PM: merged candidate, branch/config provenance, integration tests, final gate
  comparison and scheduling.
- Bandwidth worker: forced saturation, nonblocking mutants, ordering fixes and
  load-concurrency measurements.
- Latency worker: direct-refill contention/backpressure and resident-hit stage
  reductions; coordinate LSU edits with the bandwidth owner.
- SoC worker: generated socket checks, read-door lint/simulation, provisional
  storage comparison. Existing P3 commits and their test evidence are preserved
  separately until a controlled frontend integration is scheduled.

New architecture changes require a design update. Global keys retain exactly
one documented producer; plugins communicate through services and Global keys.

## Integration follow-up, 2026-10-01

`integ/nax-liveness` carries the combined candidate. Merge `7ebcf06f` brings in
`perf/ls-ooo-live`, including P4 replay, strict store issue and the precise-device
retirement interlock. The new ROB replay channel uses `RobLsReplayService` and
`LsEuService`, rather than reaching into the ROB plugin area. This merge passed
403 fast tests (2 ignored): `/tmp/codex-integ-agent54-testfast.log`.

Subsequent integration adds direct-refill collision coverage, forced WB saturation,
CPUSH invalidation, and the shipping/prepared-retirement harness fix. Their
combined final fast gate is still owed; the earlier 403 pass does not cover them.

The first combined run enabled shipping configuration, LS-OoO, NB4, D4, hot reads,
ring8, and direct-refill response. `/tmp/codex-integ-agent54-combined-ring8.log`
records four passing liveness cases and the IRQ test: 300 device writes for 300
stores across 1041 exception entries. Case C found an actual early-probe-credit
deadlock with six inhibited loads parked; its subsequent coverage assertion
masked the more useful liveness failure. The worker is correcting both the
resource leak and diagnostic ordering. This is not a passing ring8 gate.

The same run revealed the standalone CPUSH harness did not attach the hot read
port. After attachment, its clean-line DMA trigger also needed to observe that
port's R handshake with a separate ID namespace. With those harness corrections,
all five dirty/clean, scope and DC/BC CPUSH phases passed under the combined
configuration: `/tmp/codex-integ-agent54-cpush-hot2.log`. Fuzz lockstep and the
standalone inhibited-order harness also require hot-port attachment. With that
attachment, fuzz seeds 0 through 4 had zero divergences or generator failures, and
the standalone inhibited-order check passed under the combined configuration:
`/tmp/codex-integ-agent54-hot-harness.log` (two ScalaTest tests).

SoC evidence is recorded separately at `codex/bandwidth-soc` commit `3bbbaf0`,
`docs/hotdoor-integration-validation-2026-10-01.md`: full-top lint OFF/ON, 78 passing
hot-door testbench checks, and focused D4 A/C checks. Four CPU read IDs fit within
the L2's eight MSHRs and DDR bridge's eight accepted read descriptors. The shared
lookup and DDR datapaths remain throughput limits; capacity is not proof of
sustained four-way concurrency on every workload.

With LS-OoO enabled, ring4-to-ring8 adds 1558 bits of selected generated register
state: 1118 aligned descriptor/response/FIFO bits and 440 inhibited-park bits.
This excludes widened routing IDs, combinational logic, synthesis optimization,
and implementation timing. It is not an FPGA area result. The existing SoC
10-cycle/64-byte completion metric is not first-beat L2 latency; the requested
six-cycle incremental latency is still unproven.

## Odd-stack exception family gate, 2026-10-01

The corrected exception/NMI integration at `99e71482` passes all 68 named
`ExecuteLockStepSpec` cases selected by `odd-ssp:` in both matched arms. Counts,
names, and zero failures were checked separately; both arms exercised the same
68 cases, including the complete interrupt-boundary sweeps. Logs are
`/tmp/codex-odd-family-integrated-{off,on}.log`; the checked manifest is
`/tmp/codex-odd-family-integrated-results.json`.

Both arms use the shipping harness and four configured MSHRs. OFF uses the legacy
cache, ring4, and disables LS-OoO, D4, hot reads, direct refill, early response,
eager AR, P3 fast loads, probe-line forwarding, and speculative load wakeup. ON
uses NB4/ring8 and enables all those options. This closes the odd-stack family
gate for that source; it is not a full lockstep/corpus result and does not cover
the later AR-preselection experiment. The broad matched corpus at `5123593f`
continues independently; its older exception fixtures must not be confused with
this corrected-source family result.

## AR-preselection integrated gate, 2026-10-01

Integrated source `1b870987` passes the clean default fast gate (404 tests) and
all 17 nonblocking-cache tests with early response, eager/preselected AR,
P3 fast loads, probe-line forwarding, NB4/ring8, D4, hot reads and direct refill
enabled. The cache run includes four checked 1,000-operation chaos seeds.
Exact command/configuration and terminal counts are in
`/tmp/codex-agent59-preselect-integrated-gates.py` and
`/tmp/codex-agent59-preselect-results.json`; logs are
`/tmp/codex-agent59-preselect-{fast,all-options-cache}.log`.

The option remains OFF by default. Its matched full-core measurements and
write-hazard coverage are recorded in the nonblocking-cache spec section 6.3e.
These tests prove neither routed area/timing nor the target six-cycle incremental
L2 latency. Later changes require their own applicable gates.

The separate CPUSHL publication fixture correction (`e800996e`, integrated as
`be202e89`) passes its exact named corpus test with the earlier candidate's
performance configuration OFF and ON, plus 404 fast tests. The fixture now
explicitly enables IC and publishes generated code before its initial execution;
the later IC-only CPUSHL remains the operation under test. Logs are
`/tmp/codex-cpush-fixture-{off,on,fast}.log`. It is an architectural publication
and progress check, not proof of overlap with a busy maintenance FSM. It remains
excluded from forced-copyback sweeps.


## Metadata foundation integration gate, 2026-10-01

Source `0f1e4571` passes 404 fast tests, all seven queue/walker foundation tests,
and the corrected CPUSHL fixture with NB4/ring8, LS-OoO, D4, hot reads, direct
refill, speculative wakeup, early response, eager/preselected AR, P3 fast loads
and probe-line forwarding enabled. The manifest is
`/tmp/codex-agent59-um-foundation-results.json`; commands/configuration are in
`/tmp/codex-agent59-um-foundation-gates.py`. This validates the inactive-by-default
metadata foundation alongside the performance candidate. It does not implement
or validate the selected full D/I metadata ordering contract; real service
wiring and activation remain separate work.

The checked independent-chain telemetry and capacity controls are now tracked
in [the load concurrency evidence](LOAD_MLP_CHAIN_SWEEP_2026-10-01.md). Those
measurements deliberately keep AR preselection OFF and are not a combined
candidate throughput claim.
