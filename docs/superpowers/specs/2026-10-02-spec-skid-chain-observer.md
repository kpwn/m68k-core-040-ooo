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
