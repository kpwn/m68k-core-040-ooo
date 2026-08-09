# Registered-token fetch-directed FTB implementation plan

Status: **READY AFTER P0.** This plan replaces the blocked
`2026-08-09-ipc-fetch-directed-btb-implementation-plan.md` and implements the
binding token-pipeline amendment. Do not execute RTL tasks until P0 proves the
current IPC branch reaches the floorplanned 200-MHz hard floor and records its
gap to the standing 250-MHz optimization goal.

## Global rules

- Architecture owner:
  `docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-token-pipeline-amendment.md`.
- Use an isolated reserved worktree for every edit and A/B comparison.
- Preserve both decode-time BTB ports in phase 1.
- Plugins communicate through services; no new plugin-internal reach-through.
- Any new `Global` key has one documented producer. Prefer constructor parameters
  for `ftbEntries=128` and `ftqDepth=32`.
- Standing optimization goal: 250 MHz in the floorplanned post-route flow.
  Hard deployment floor: 200 MHz. Keep the 4-ns constraint for closure and
  comparable census data; a 200–250 MHz result remains explicit timing debt.
- Area gate: <= +1.0% full-core LUT, <= +2,500 FF, no BRAM/DSP growth.
- If the area gate is exceeded, do not automatically reject or delete the
  feature. Report exact utilization and pblock deltas, structure attribution,
  measured IPC, and concrete reduction choices to the project owner first.
- Every test counts the handshakes/events it claims to cover and includes a
  mutation or negative control for its load-bearing assertion.
- Mandatory handoff gate: `make SBT=~/sbt/bin/sbt test-fast`.
- Full Verilator, ported corpus, and Vivado remain PM-serialized.

## P0 — current-branch physical checkpoint

1. Wait for the shared Vivado lock; do not overlap the SoC implementation.
2. Generate current full-core Verilog from `fca42f4` or its exact descendant.
3. Run the existing floorplanned implementation and census.
4. Record achieved FMax, WNS/TNS, failing endpoint families, global utilization,
   and every pblock's capture/utilization/congestion.
5. If achieved FMax <200 MHz, stop this plan and recover timing/floorplan first.
   If 200–250 MHz, commit the checkpoint evidence, record the debt and recovery
   options, then continue only after deciding whether another recovery slice is
   cheaper than deferring the feature. At >=250 MHz, continue with full headroom.

## P1 — inert training metadata

### Changes

- Add `len: UInt(4 bits)` to `BtbUpdate`.
- Compute exact fall-through length in `BranchEuPlugin` from
  `(u1.nextPc-u1.pc)>>1`; carry it atomically through `BranchCompletion`, the
  existing ROB branch-training memory, and the registered retire update.
- Existing `BtbPlugin` ignores `len`; no prediction behavior changes.

### Proof

- Branch-update test covers one-, two-, three-, and longer-word control forms,
  ROB wrap, flush, and non-branch suppression.
- Paired IPC is cycle-identical with the field inert.
- `test-fast` green; no physical gate required for a payload-only inert slice
  unless synthesis shows the field unexpectedly survives outside training state.

## P2 — registered FTB and gshare window services, inert

### Changes

- Add plain bundles and `FtbLookupService`/`GshareWindowService` in Services.
- Add `FtbPlugin(entries=128)` with one async-read table, registered fixed-latency
  result, retire-time write, clear-one, and full invalidation.
- Extend `GsharePlugin` with four parallel window-offset PHT reads and a registered
  result carrying the same token. Do not shift GHR here.
- Add FTB instances and service wiring to full core, IPC bench, fuzz, lockstep,
  and dedicated synthesis/test DUTs. No top-level test-only hardware shortcuts.
- Drive both query commands false; application remains absent.

### Proof

- Unit test sends eight distinct queries in eight cycles and checks eight exact
  next-cycle tokens/results, training collisions, not-taken conditional storage,
  clear-one, update+invalidate priority, and reset.
- Gshare test proves all four indices/taken bits against the scalar index function
  for randomized PCs/GHR and consecutive changing PCs.
- `test-fast` green.
- Run a floorplanned route: inert storage must preserve the 250-MHz goal where
  possible and must meet >=200 MHz plus the area budget. A sub-200 result pivots
  immediately to endpoint-driven recovery; a 200–250 result is reported as debt.
  An area failure pauses for
  the explicit owner review required by the global rules before reshaping or
  connecting it to FetchAlign.

## P3 — ring association and held successor, prediction disabled

### Changes

- Add `ringKeep`, `ringPlanSeq`, `planSeq`, and the held `{target,drop}` slot.
- Query both services exactly on `ic.cmd.fire` using `{ringTail,planSeq}`.
- Join registered results by token and expose an `applyEnable` register defaulted
  false. With it false, consume results without changing command selection.
- Change response word count from `4-drop` to `keep-drop`; default keep is four.

### Proof

- Eight command fires produce exact service query tokens.
- Full-ring consume/replace followed by result verifies the new entry, not the
  consumed entry, receives the matching sequence.
- Wrong sequence/stale/current-redirect result does nothing.
- With application disabled, frontend cadence, feed sequence, and IPC are exactly
  baseline-identical.
- `test-fast` green. No Vivado gate unless the disabled join survives synthesis in
  the enabled topology; otherwise combine with P4 for an honest netlist.

## P4 — fetch-side application and FTQ

### Changes

- Add the 32-entry memory-backed FTQ with head/tail/count and flush.
- Implement `applyNow`, atomic ringKeep/FTQ update, direct C+1 target selection,
  same-cycle target drop selection, and held target under cache backpressure.
- Do not flush/stale existing fetch state and do not shift GHR.
- Add application and decline-reason counters for verification/IPC reporting.

### Proof

- Train W→T, issue W in C, require T in C+1 and forbid W+8.
- Hold cache ready low on C+1: one truncation and FTQ push, stable target/drop,
  then one target fire.
- Exercise target-in-same-window loops, target offsets 0..3, ring pointer wrap,
  direct-map collisions, conditional not-taken decline, and FTQ bound assertion.
- Mutation to `RegNext(fetchPc)==fetchPc` must fail the consecutive application
  count; mutation to registered-only pendingDrop must fail an unaligned C+1 target.
- `test-fast` green; focused simulation required before P5.

## P5 — decode clamp, confirmation, and recovery

### Changes

- Implement `availEff` and use it at both the aligner and live-predecode validity.
- Add FTQ head distance/confirm/mismatch state and starvation dwell.
- OR `slot1WouldFtq` with the retained slot-1 BTB and RAS defer terms.
- On confirm, stamp target/PHT index, suppress slot1, shift GHR once at decode,
  jump decodePc to target, and pop FTQ without flushing target bytes.
- On mismatch, invoke the existing redirect/flush action, clear one FTB entry,
  and arm one-shot suppression.
- Every architectural frontend flush also clears FTQ and held target.

### Proof

- Port the parent A1-A10 framing matrix with explicit event counters.
- Correct branch: target bytes are already buffered, confirm emits once, and no
  I-cache restart occurs.
- Incorrect length/boundary/target: only genuine packets retire and the exact
  architectural trace matches a prediction-disabled run.
- FTB miss/collision/cross-window/second branch/return prove both existing BTB
  ports and RAS remain live. Muting either fallback must fail.
- Maintenance invalidation clears FTB before modified code executes.
- `test-fast`, focused lockstep, and focused ported control-flow tests green.

## P6 — enable and measure

1. Enable application in the production topology only after P5 is green.
2. Run paired pinned seeds under `IPC_MEM=zero` and `IPC_MEM=l2:5:70`.
3. Report every kernel; require measured target-gap reduction, nonzero application,
   correct/declined/mismatch counts, and <=2% regression for uncovered/control
   kernels. Do not hide regressions inside an aggregate.
4. Run the complete lockstep and ported-test gates with exact fail-list comparison.
5. Run paired floorplanned post-route and census. Target >=250 MHz, require
   >=200 MHz, keep area within budget, prevent pblock overflow/congestion
   regression, and inspect both new path families named by the amendment.
6. If timing fails, pipeline or localize the registered-result/command boundary;
   never replace it with an unregistered FTB lookup. If IPC fails, retain the
   measured fallback and remove the feature rather than weakening correctness tests.
   If only area fails, report the complete tradeoff and wait for owner direction
   before removing, shrinking, or changing the feature.

## P7 — landing

- Update the parent design/plan statuses to implemented or rejected using measured
  evidence.
- Record per-kernel IPC, application coverage, target gap, tests, area, FMax, and
  pblock results in the IPC ledger.
- Commit each coherent slice separately; never leave a multi-slice working diff.
