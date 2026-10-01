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

## Frozen broad-corpus comparison checkpoint

Both `PortedM68kOooSpec` arms at frozen `5123593f` are complete, with the same
1024 registered cases. OFF has 14 failures, ON has those same 14 plus
`cpush_ic_maint_while_busy`; there are no OFF-only failures. The validated
name/count manifest is `/tmp/codex-candidate-gates-5123593f/results.json`.
The ON lockstep arm is still running, so the four-arm broad gate is not complete.

| Failure group at the frozen source | Count common to OFF/ON | Current disposition |
| --- | ---: | --- |
| FPU fixtures (six names plus three copyback variants) | 9 | Corrected fixtures have focused gates; newer-source full corpus is still owed |
| `exc_partial_macro_move_mem_mem` | 1 | Format-7 pending-write/retirement gap confirmed; implementation pending |
| MMU modified bit and two older-store/walker cases | 3 | Selected D/I metadata-ordering implementation in progress |
| `rom_scc_mmio_btst_dbf_timeout` | 1 | No SCC model at the polled peripheral address; harness fill is not a peripheral oracle |
| `cpush_ic_maint_while_busy` (ON only) | 0 common; 1 ON-only | Initial code-publication setup corrected and matched focused OFF/ON plus all-options integration pass |

Focused fixes do not turn the older frozen corpus red rows into a broad PASS.
Keep that distinction when reporting the current candidate. The SCC case must
not be hidden by accepting a failure sentinel or changing global memory fill.


## Dynamic-release physical inputs (2026-10-01)

Frozen integration source `46413280` was elaborated with actual
`GenSocketTopVerilog`, throughput-v2/full-debug, NB4/ring8/hot read door,
P3/probe forwarding/speculative wakeup/LS-OoO, early response/eager/preselected
AR enabled and direct refill disabled. The paired arms differ only in
`CPU_DCACHE_NB_DYNAMIC_RELEASE=0/1`. Both elaborations succeeded and their
117-port socket surfaces compare equal, including direction and width.
This is paired interface preservation, not complete CPU/SoC conformance.

Inputs and all generated-file SHA-256 digests:
`/tmp/codex-dynamic-physical-inputs-46413280/manifest.json`.
Runner: `/tmp/codex-dynamic-physical-inputs.py`.
`M68kSocketTop.v` SHA-256:

- OFF: `c0079e4d518d20733b2e723bf0d3da5a9f8a5c21f6ee0466e3fb7b8507152a72`
- ON: `ee40fdbd306ff2e321d20427b051f2dcfd012543d4b023b89f9fc93494f95f00`

No synthesis, placement, routing or board action was performed. These artifacts
prepare a controlled comparison; they do not establish LUT/FF cost, Fmax, SoC
clock closure or board IPC. `synth/impl_FullCore.tcl` targets the standalone
full-core top, not this socket top: do not feed these files into that flow
under its existing top/constraint names. A socket/SoC implementation must retain
the recorded configuration and coordinate the shared implementation lock and
active Verilator jobs per `synth/README.md` before it runs. Preserve the live
board/JTAG session. Feature defaults stay unchanged.


### Explicit hot-door port checking

The core socket checker now accepts `--hot-door` only as an explicit generation
contract. It requires all 13 read-only AXI hot-port names, directions and widths
(128-bit R data, 2-bit IDs); it rejects missing groups, unexpected groups when
not enabled, write channels and sidebands. A missing socket file now fails
instead of silently skipping validation. The frozen full-core port golden is
unchanged. This implements the core-side checker requirement in D-side spec
§7.3; it does not activate or modify the SoC wiring.

Both actual generated socket arms above passed the socket checks. Eleven
controls/mutations in `/tmp/codex-hot-socket-check.py` verified missing RID,
wrong data width, reversed ready direction, unexpected write/sideband ports,
missing or unannounced hot group, hot-disabled control and missing file
rejection. Results: `/tmp/codex-hot-socket-check-results.json`. These checks
called `check_socket` directly, so they do not claim to have run the separate
standalone full-core netlist check. Required `test-fast` passed 404/404 on source `99ecd1fb`; log
`/tmp/codex-hot-socket-fast.log`. Integration source `ef2a0c45` then passed
the same 11 socket controls and required default `test-fast` (404/404,
2 ignored). Evidence: `/tmp/codex-integrated-hot-socket-check-results.json`,
`/tmp/codex-integrated-hot-socket-results.json`, and
`/tmp/codex-integrated-hot-socket-fast.log`. No production RTL changed in
this checker integration; physical and full-corpus acceptance remain open.

Read-only inspection found the live SoC main tree `dc97a83` still labels the
CPU hot interface planned, while its `dhcpu` worktree has uncommitted P6 wiring
with the expected 2-bit ID and 128-bit data constants. Those owner changes and
the live board session were left untouched. Full two-repository integration,
lint/protocol tests and physical acceptance remain required.


## Combined full-core ordering checkpoint (2026-10-01)

On frozen `46413280`, NB4/ring8/hot door with dynamic release, early response,
eager/preselected AR, P3, probe forwarding, direct refill, speculative wakeup,
LS-OoO and the shipping LSU profile passed `LsOooStressSpec` seeds 14–17 at
120 generated operations each, and `InhibitedStoreIrqReplaySpec`. The random
stress checked device read/write counts and split accesses: 6,812 commits,
1,960 exception entries, 29 replays, 15 replay redirects and zero stress
failures. Seeds 15 and 17 injected IRQ storms. Exact configuration and the
original three-suite run are preserved in
`/tmp/codex-agent59-dynamic-core-results.json` and
`/tmp/codex-agent59-dynamic-core.log`.

The third suite, `LsOooInhibitedOrderSpec`, initially failed its D0 enqueue
monitor: it treated P3 fast enqueues as P4 enqueues and inspected unrelated
P4 context. Test-only commit `d3015075` distinguishes P3, P4 and parked
sources. It retains the independent actual-launch head/order assertions,
exact per-device counts and AXI/launch one-to-one check; it additionally
requires a P3 enqueue when that option is enabled. The corrected fixture
passed on the same configuration with P3 ON and OFF: both observed exactly
8 inhibited launches/8 device reads, 31 cacheable launches, 7 order redirects,
and zero D/D0 or overlapping-inhibited violations. P3 ON observed 10 P3
admissions; OFF observed zero. The required default `test-fast` passed 404.
Manifest: `/tmp/codex-agent59-enqueue-monitor-results.json`; logs:
`/tmp/codex-agent59-enqueue-monitor-{on,off,fast}.log`.

No production RTL changed between `46413280` and `d3015075` (`git diff` of
`src/main` is empty), so the two passing original full-core suites and the
paired generated socket inputs still describe this RTL. The originally
failed suite remains explicitly recorded above. These checks supplement the
18-test integrated cache gate; they do not close pending MMU/fault defects,
full corpus comparison, all nonblocking mutants, or physical/board acceptance.

## Candidate CPU with SoC hot-read binding (2026-10-01)

The generated dynamic-release ON socket from CPU `46413280` was linted as
an actual `fpga_top` instance with the hot-read guard and L2 binding. The
first frozen snapshot of the owner's uncommitted `dhcpu` tree passed basic
lint but failed shipping lint: the VIO v28 instance connected 32-bit
`probe_in15`, absent from the Verilator primitive stub. Its original logs
and hashed inputs remain in `/tmp/codex-soc-hot-integration-46413280/`.

The maintained clean SoC branch `83f7f96` already includes the hot-door
integration and stronger NB/D4 build guards. Isolated SoC commit `6086cf4`
adds only the missing 32-bit stub input, matching `gen_debug_vio_ip`'s v28
width list. A new frozen snapshot of that commit plus the same generated
CPU passed both `make lint-fpga-top` configurations: basic and shipping
(VIO, JTAG AXI and disabled SD JTAG writer). Both explicitly set
`CPU=m68k040`, `CPU_AXI_DH=1`, `CPU_DCACHE_NONBLOCKING=1`, and
`CPU_INHIBITED_FULL_BARRIER=1`. Inputs, exact commands, exit codes and logs
are in `/tmp/codex-soc-hot-integration-6086cf4/{manifest,results}.json` and
`{basic,shipping}.log`. Neither run regenerated the recorded CPU artifact.

This validates elaboration and port binding against the existing SoC lint
rules, which suppress several warning classes including width and missing
pins; the separate strict socket checks above cover the generated hot-port
schema. It does not establish functional CPU/SoC ordering, routed timing,
area or a board boot. The later experimental L2 hot-response fall-through
is not present in this validated SoC source. Owner worktrees and the live
board/JTAG session were not modified.

## Completed frozen broad comparison (2026-10-01)

The long-running `5123593f` comparison is now terminal with all four expected
suites complete. Ported OFF/ON each ran 1,024 cases, with 14/15 failures.
Execute lockstep OFF/ON each ran 703 cases with 10/11 failures and the same
one skipped case. ON-only failures are `ported: cpush_ic_maint_while_busy`
and `odd-ssp: level-1 IRQ at every boundary of the LINK #-75 stretch,
boot-10, handlers call`; there are no OFF-only failures. This frozen run is
red, not a passing regression comparison. Exact sets and failures are in
`/tmp/codex-candidate-gates-5123593f/{results,comparison}.json` and XML logs.

The extra lockstep failure reports an ambiguous old boundary oracle at
boundary 41 (DUT PC `408000b2`, last attempted oracle PC `408000be`). The
later corrected odd-SSP family includes that exact test name and passed
68/68 cases in each arm on `99e71482`, recorded in
`/tmp/codex-odd-family-integrated-results.json`. The later CPUSH fixture
validation is recorded above. Those focused results do not replace the
full frozen results or establish full-corpus acceptance of today's RTL.
The preserved watcher has started its separately queued full-copyback
supplement on the same older source; that supplement remains in progress.
