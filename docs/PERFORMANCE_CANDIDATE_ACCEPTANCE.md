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
