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

## Follow-up: credit state at refused allocation

The 609 FILLED/LINGER overlap samples do not identify reclaimable credit.
Add simulation-only visibility of the existing NB install candidate/winner,
install hold, store reservation, S2 copyback reservation, waiter index,
same-edge waiter add, and `lFreeOk` predicates. No allocation, install,
release, or response RTL may change. Keep the prior throughput and skid
counters unchanged.

The `failFull` counter is a register: if it rises between probe samples T
and T+1, report the **pre-edge T** cache state that attempted allocation,
and confirm the post-edge T+1 state for any predicted LINGER release. This
alignment is separate from the existing legacy `DSIDE_CHAIN_FAIL_FULL`
snapshot, which historically prints the T+1 state. Sample only attempts
whose counter increment lies inside the measured commit window.

For each attempted allocation, partition per-entry state without claiming
the buckets are disjoint across entries: FILLED awaiting invalidation,
FILLED with install hold, FILLED eligible for install but not selected, and
FILLED selected by the one install port; LINGER blocked by registered S1 or
staged-set, live waiter, or same-edge waiter add; LINGER eligible to become
FREE at the edge. Report event-cycle counts and slot-sample counts for each,
plus events with no completed entry. Reconstruct `freeAfterS` by excluding
the store-reserved slot from old-state FREE bits, and report events where an
S2 copyback reservation requires an extra free slot. Assert the observed
`lFreeOk` equals `freeAfterS >= 1+s2CbRes`, and predicted release entries
become FREE in the next sample. An eligible LINGER is a **next-edge** credit
opportunity, never a same-edge allocation; FILLED winner is at least a
subsequent LINGER edge away. A one-seed diagnostic can identify a specific
dominant avoidable hold, but does not prove throughput gain or an RTL fix.

The frozen diagnostic source `0bbe08d8` passed the seed-17 indexed
four/eight-chain run in `/tmp/codex-agent78-credit-seed17-r2.log`; the
measured windows remained 10,335/10,689 cycles and the eight-chain counter
still recorded 1,503 failed allocation **increments**. The earlier attempt
on `483b9085` stopped before architectural checks because the test read an
unnamed `instCand.asBits` simulation temporary; `0bbe08d8` reads the public
per-entry predicates. `make test-fast` then passed 404 tests on `0bbe08d8`
in `/tmp/codex-agent78-credit-fast.log`.

| Pre-edge reason at eight-chain `failFull` | Event cycles | Entry slot-samples |
|---|---:|---:|
| No FILLED or LINGER entry | 967 | — |
| FILLED install selected | 266 | 266 |
| FILLED eligible install candidate | 266 | 274 |
| FILLED install held | 56 | 58 |
| FILLED pending invalidation or fault | 0 | 0 |
| LINGER eligible to become FREE at this edge | 247 | 247 |
| LINGER held by registered S1/staged-set | 1 | 1 |
| LINGER held by waiter or same-edge waiter add | 0 | 0 |
| Store slot or S2 copyback reservation | 0 | — |

Categories can coexist in an event; 266, 56, and 247 must not be added as
distinct failed cycles. All 247 eligible LINGER entries became FREE in the
post-edge sample, and the observed `lFreeOk` matched old-state FREE count
after store/S2 reservations on every attempt. Holding LINGER for one more
sampled edge therefore accounts for at most 247/1,503 (16.4%) attempted
allocations in this run, with no proven IPC benefit. The legacy
`DSIDE_CHAIN_FAIL_FULL` row samples post-edge state and reports 609 events
with FILLED/LINGER; its number is not the same pre-edge classification.
The existing replay lookahead requires two actual FREE slots, so an entry
becoming FREE alone does not guarantee immediate replay admission.
