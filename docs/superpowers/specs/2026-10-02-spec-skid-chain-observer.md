# Speculative LS hold / independent-ready load observer

This is measurement-only work on integrated `85b6eece`. The existing
`chase-chains-4/8-1024-skew0` kernels, seed 17, and all scheduling RTL remain
unchanged. Count only samples in `runKernel`'s measured commit window, using
the same clock alignment already checked by `DSIDE_CHAIN_WINDOW`.

There are two potentially overlapping hold states. **A** is
`lsSpecBlocked && lsSkidValid`: an unconfirmed speculative consumer occupies
the issue register and another uop occupies the skid. **B** is
`lsSkidValid && skidSpecBlocked`: the skid payload's read A/C physreg is still
marked `lsBusy`, so that payload itself was speculatively released. Report A,
B, and A∪B cycles independently; do not sum A+B as distinct cycles. Also
record skid-valid cycles outside A∪B as a control. Publish only existing
register fields and a simulation-only skid predicate; no new architectural
state, Global key, or selection logic is allowed.

For every occupied LS LOAD slot, check the authoritative registered `ready`
against its source conditions: all lower-priority static `triggers` clear,
all `dynWait` bits clear, and `dynWaitAny` clear. Count any mismatch separately
as an observer failure. A **confirmed independent-ready** candidate also has
no still-`lsBusy` actually-read A/B/C source (speculative clear may have made
`ready` true before data exists); no older occupied IQ STORE; and, if any
older occupied LS slot is unready, `firstOfInstr` true. Exclude flush cycles
and candidates younger than an unissued STORE held in the issue register or
skid. Classify candidates as oldest or younger among occupied LS slots, and
count both candidate slots and cycles with at least one candidate under A,
B, and union. Verify candidate ROB IDs differ from held issue/skid IDs.

These are **IQ-stage upper-bound opportunities**, not legal LS grants:
translation/cacheability, P4 inhibited/SQ barriers, ring credit, and current
LS-EU payload-dependent readiness are not established at IQ. Cross-tab actual
same-window `lsSelectLoadFire`, `lsEuFire`, load-command fire, and hot AR with
the hold states to separate offered work from delivered overlap. Preserve
the existing throughput and architectural-data checks exactly. The initial
diagnostic uses SPEC_WAKE ON only; a selection experiment is not authorized
by this observer.

## Measured result (2026-10-02)

The frozen source was `7f6b4cd7`; the focused log is
`/tmp/codex-agent78-spec-skid-on-r2.log`. The configuration was NB4/ring8,
hot door, LS-OoO, fused long MOVE loads, SPEC_WAKE ON, P1 OFF, direct refill
OFF, and `IPC_MEM=l2:5:60:4096`. Both indexed-chain kernels used seed 17,
1024 records, zero set skew, and the same measured commit window as the
existing throughput counter. Architectural checks passed. The four-chain
window stayed at 10,335 cycles and the eight-chain window at 10,689 cycles,
identical to the pre-observer rows. Registered-ready and dynamic-OR
consistency mismatches were both zero. `make test-fast` passed 404 tests in
`/tmp/codex-agent78-spec-skid-fast.log`.

| Indexed kernel | Window cycles | A cycles | B cycles | A∩B | A∪B cycles | A∪B cycles with candidate | Candidate slot-samples | Oldest / younger slot-samples |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 chains | 10,335 | 6,218 | 8,104 | 6,125 | 8,197 | 13 | 13 | 13 / 0 |
| 8 chains | 10,689 | 122 | 42 | 36 | 128 | 90 | 201 | 62 / 139 |

Thus four-chain candidate opportunity exists in only 13/8,197 hold cycles
(0.16%; 0.13% of the whole window). The eight-chain case has 90/10,689
candidate cycles (0.84% of the window), including younger slots; its 201
slot-samples do not imply 201 unique dynamic instructions. The printed
`uniqueCandidateRobIds` counts distinct **ROB tag values**, which may recur
after ROB wrap, not distinct dynamic instructions. A and B overlap heavily,
and exclusion buckets (unconfirmed source, older stores, intra-macro, etc.)
can overlap; neither should be summed. The candidate is an IQ-stage upper
bound because LS-EU credit, P4 translation, barriers, and cacheability have
not been qualified. These counts give no evidence for changing LS selection
priority on the current indexed kernels.

At eight chains the unchanged telemetry showed 1,503 `failFull` attempts,
including 609 cycles with at least one FILLED or LINGER entry, and 299 cycles
with only WAIT_R entries. That 609 is an overlap upper bound, not a count of
reclaimable credits: FILLED still needs the single install port, while dynamic
LINGER release must retain the CAM entry through registered S1/staged reads,
live waiters, and a same-edge waiter addition. This observer makes no claim
that shortening either state would recover those attempts.
