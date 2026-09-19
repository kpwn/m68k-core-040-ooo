# Core repository cleanup — 2026-09-19

This pass changes packaging, documentation and tooling defaults, not CPU RTL.
Published Git history is preserved. Removed files remain recoverable from
commit 0edb3fc41e2a613089ac51c1b9b738242771fc2e, for example:

```sh
git show 0edb3fc41e2a613089ac51c1b9b738242771fc2e:run_fuzz200.sh
```

Removed six one-off root launchers: obsolete private scratch paths, historical
source-rewriting A/B experiments, and a one-shot P135 build wrapper. Reusable
tools/fuzz/ runners, tools/pm_gate.sh and synthesis scripts remain. Full tests
still require coordination; removing a launcher is not permission to run
concurrent heavy campaigns.

Removed 16 raw timing dumps; preserved concise what-if summaries, gate results,
design documents, bug investigations and reproductions. Historical document
references to the removed paths refer to the commit above, not live files.

Removed paths:

- `run_fix_lockstep.sh`
- `run_fuzz200.sh`
- `run_ispec_gateoff.sh`
- `run_ispec_predicate_sweep.sh`
- `run_ispec_reverify.sh`
- `run_p135_gate.sh`
- `synth/probe_dcache_iq/ladder.txt`
- `synth/probe_dcache_iq/sbInt_busy_startpoint_census.txt`
- `synth/probe_dcache_iq/stS1_paddr_endpoint_census.txt`
- `synth/probe_dcache_iq2/iq_startpoint_census.txt`
- `synth/probe_dcache_iq2/ladder_after_AB.txt`
- `synth/probe_endpoint_cones/endpoint_ladder.txt`
- `synth/probe_itlb_hitway/deep_ladder.txt`
- `synth/probe_pinkind/pinkind.txt`
- `synth/probe_population_after_cuts/population_after_cuts.txt`
- `synth/probe_slack_population/logic_levels.txt`
- `synth/probe_slack_population/population_m0p500.txt`
- `synth/probe_slack_population/population_m1p000.txt`
- `synth/probe_specsize/ladder_nopredict200.txt`
- `synth/probe_specsize/ladder_ref200.txt`
- `synth/probe_specsize/ladder_to_specsize.txt`
- `synth/probe_specsize/specsize_endpoints.txt`

The README now distinguishes the framework-only Verilog generator from the
full CPU, reflects the tested 200 MHz SoC release, and no longer lists the
implemented FSAVE/FRESTORE computed-EA forms as missing. The user's introduction
is unchanged. Worktree-pool and optional sibling-check defaults no longer assume
a particular user's home directory.
The host debug-map test's stale expected feature list now includes the already
implemented fetch_word_check bit 25; the register definition and RTL are unchanged.

Run `make check-publication` after staging changes. It checks the Git index,
not ignored local outputs or fetched submodule contents, and is not a secrets
scanner or legal clearance. It rejects firmware/binary images, checkpoints,
logs, runtime metadata and raw timing dumps. This cleanup does not shrink old
Git history or remove anything from the running board.

Validation commands:

```sh
make check-publication
bash tools/test_agent_worktree_pool.sh
bash tools/debug/run_tests.sh
make SBT=~/sbt/bin/sbt test-fast
```

The optional debug sibling-conformance check reports SKIP without the legacy
sibling reference files; the local debug map/protocol and generated-file checks
run independently. No full RTL suite or FPGA rebuild is implied by this cleanup.
