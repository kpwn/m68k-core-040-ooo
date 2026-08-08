# FMax closure, "Frontend Lever A": delete the tautological `i==0` word mask (design)

## Context

Part of the same combined-slice effort as "LS/ROB Lever A"
(`docs/superpowers/specs/2026-08-08-fmax-levera-reqmatch-freshness-design.md`,
being implemented in parallel — disjoint files, `LsEuPlugin.scala` vs
`Aligner.scala`, zero cell overlap confirmed at the netlist level). A
netlist-grounded investigation
(`.../scratchpad/fmax-frontend-dstea-ce-grounding-report.md`) traced the
design's current WNS-holding path cell-by-cell against the live post-route
checkpoint and found a genuinely new, MEASURED lever — distinct from both
prior frontend attempts this session (Slice 3's disproven "chained shift"
mechanism, and `moveLineSize`'s wrong-OOC-target/non-convergent throwaway).

**Both prior frontend attempts are DEAD ENDS for documented, netlist-proven
reasons — do not revisit either mechanism.** This lever is different in
kind: it removes a genuinely redundant computation (a mask that is always
true given an existing guard condition), not a data-path restructuring.

## The mechanism (source-cited, netlist-confirmed, current HEAD post-Slice-3-revert)

`Aligner.scala`'s slot-1 word-select loop:

```scala
// Aligner.scala:214-219
val idx = (L0 +^ U(i)).resize(4)
when(U(i) < L1) { r.slot1.words(i) := words(idx) } .otherwise { r.slot1.words(i) := 0 }
```

and slot-0's analogous loop:

```scala
// Aligner.scala:151-157 (same shape, guarded by L0 instead of L1)
when(U(i) < L0) { r.slot0.words(i) := words(i) } .otherwise { r.slot0.words(i) := 0 }
```

At `i == 0` these reduce to `when(0 < L1) { ... }` / `when(0 < L0) { ... }`
— a mask on the OPWORD ITSELF (the header word every downstream decode
gate needs first). **This mask is a tautology**: both loops execute only
when the corresponding slot is already known to be `simple`:
- Slot 1: `slot1Ok = p1.simple && !p1.ambiguousLine && (avail >= L0+L1) &&
  (L0+L1 <= WINDOW)` (`Aligner.scala:202`) — `words(i)` is populated only
  inside `when(slot1Ok)`.
- Slot 0: populated inside `.otherwise` arms gated on `p0.simple` being
  true and `avail >= L0` (`Aligner.scala:143`, `:145`).

**The property this relies on**: `PredecodeWord.classify(...)` (the
function that produces `simple`/`lenWords`, `frontend/PredecodeWord.scala`)
never returns `simple === True && lenWords === 0`. If that property holds,
`0 < L1` (resp. `0 < L0`) is always true whenever the mask's guard is even
reached, and the `i==0` branch of the `when` can be deleted — the opword
becomes available WITHOUT waiting for `L1`/`L0` to resolve first.

**Netlist-measured effect** (see grounding report §1.1/§3.4 for the full
cell-by-cell trace): on the design's current WNS-holding path
(`ibuf pred_lenWords[0] -> DecodeStage specs_1_dstEa_disp[28]`, -2.062ns),
levels 5-7 (`preds(L0)` 10:1 mux -> `L1` -> the `i==0` mask) cost
**1.066ns / 17.6% of the total 6.042ns path**. A directly-measured sibling
path (routing through the UNMASKED `words(L0)` data mux instead of the
`preds(L0)->L1` select mux, `report_timing -through <unmasked opword net>`)
shows the opword is ready **0.318ns earlier** without the mask — a
MEASURED FLOOR, not an estimate (that exact routed path already exists in
the netlist; nothing is extrapolated). Deleting the mask is expected to
buy **0.32-0.56ns** on this specific endpoint.

**This is the same general shape (deep decode cone gated by a redundant
predicate) that both prior attempts targeted, but the redundant part this
time is upstream of the OperationDecoder table entirely — it removes `L1`
(slot 1) / `L0` (slot 0) from the decode cone's dependency chain, rather
than trying to shave depth inside `OperationDecoder`/`EaDecoder` (both of
which were shown, by direct measurement this session, to have no cheap
single-term reduction — see the grounding report's §4.4, "no single-term
reduction of `spec.size` can work").**

## The fix

Delete the `i == 0` special case from both loops. Concretely (exact
mechanism, implementation may express this more idiomatically — e.g. an
`if (i == 0)` Scala-level branch inside the loop body that unconditionally
assigns `words(0) := words(L1-relevant-index)`, skipping the `when(0 < L1)`
guard entirely for that one iteration, while leaving `i >= 1` completely
untouched):

```scala
// slot 1 (Aligner.scala:214-219), i == 0 case only:
// OLD: when(U(0) < L1) { r.slot1.words(0) := words(idx) } .otherwise { r.slot1.words(0) := 0 }
// NEW: r.slot1.words(0) := words(idx)   // idx = (L0 +^ U(0)).resize(4) = L0, unconditional
```

and symmetrically for slot 0's `i == 0` case against `L0` (not `L1`).

**`i >= 1` masks are NOT touched — they are load-bearing.** Slice 3's own
post-mortem correctness derivation (see
`docs/superpowers/specs/2026-08-07-fmax-slice3-frontend-dsteashift-collapse-design.md`'s
"POST-IMPLEMENTATION CORRECTIONS" §3, and `MicroOpAssembler.scala`'s
comment above `computeOffload`) established that the offloaded `dstEa`
computation's correctness DEPENDS on the zero-fill for `j >= L1` (the
region beyond the instruction's own framing must read as zero, not real
next-instruction words, for the "consumed region is strictly inside L1"
argument to hold). This slice must not touch that property — it only
removes the redundant mask at the ONE position (`i==0`) that is
architecturally guaranteed non-zero-length whenever it's reached at all.

## The proof obligation (binding — this is the entire risk of this slice)

**Required, exhaustive, must run to completion (not abort at first
mismatch):** `PredecodeWord.classify(op, ...).simple === True` implies
`PredecodeWord.classify(op, ...).lenWords >= 1`, for **all 65536 possible
16-bit opwords** (with `extW`/`extW2`/`extW3` — the grounding report's own
grep found every `r.simple := True` site in `PredecodeWord.scala` visibly
co-assigns `lenWords >= 1` at ~30+ distinct call sites, but this is
GREP-LEVEL EVIDENCE, not proof — a purpose-built exhaustive elaboration-
time test is required before this slice may be considered verified).

**Explicit standing warning, do not repeat this mistake**: this project's
own `PredecodeWordSpec`'s existing "exhaustive 65536-opword" test ABORTS
AT FIRST MISMATCH and has been distrusted for exactly this reason in a
prior session finding — do NOT reuse it or model the new test on its
early-abort pattern. `MicroOpAssemblerOffloadSpec`'s post-Slice-3
exhaustive-opword test (which ran all 65536 to completion and reported a
count) is the correct precedent — follow that shape.

`PredecodeWord.classify`'s full 3-word-lookahead signature
(`op, extW, extW2, extW3, extWValid, extW2Valid, extW3Valid`) means a
truly exhaustive sweep is over more than just the 65536 opwords if `extW`/
`extW2`/`extW3` genuinely affect whether `simple`/`lenWords` moves into the
dangerous zone — the implementation must determine (by reading
`PredecodeWord.scala` in full) whether `lenWords === 0 && simple === True`
could ONLY arise from `op` alone, or whether some combination with the
ext words matters too, and scope the exhaustive sweep accordingly (do not
assume `op`-only sweeping suffices without checking — a `simple := True`
site that depends on `extW`'s content for its `lenWords` value must be
swept over the relevant `extW` range too, or explicitly justified as
already covered by a fixed/representative `extW` if `lenWords` at that
site can be proven independent of `extW`'s value from the source).

## Non-goals

- `i >= 1` masks in either loop — untouched, load-bearing (see above).
- Slice 3's mechanism, `moveLineSize`'s mechanism, `OperationDecoder`'s
  internal structure, `EaDecoder`'s internal structure — all untouched.
  This slice's entire footprint is the `i==0` special case of two existing
  `when` blocks in `Aligner.scala`.
- The "Lever B" (bake `size` into `ChunkPredecode`) and "Lever C"
  (structural register split) options named in the grounding report — both
  explicitly out of scope for this slice (Lever B needs its own expensive
  cross-decoder-equivalence verification pass; Lever C costs a pipeline
  cycle and needs its own design pass for the `headPtr` feedback-loop
  hazard the grounding report names). Candidates for a FUTURE slice if this
  one's measured effect is insufficient.
- The `ucPendPkt_words_0` 11-endpoint family (currently -1.833ns) — the
  grounding report notes this becomes the new binding constraint the
  moment this lever lands. Out of scope for THIS task; characterize/report
  it in the verification step (does it become the new frontend WNS-holder
  post-change, per the design's own prediction) but do not attempt to fix
  it here.

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- **The exhaustive proof test described above** — this is the load-bearing
  verification for this entire slice, more so than any synth number.
- `AlignerSpec` full suite: expect 9/9 (this session's standing baseline).
- `FetchAlignSpec`: expect the same single pre-existing unrelated failure
  this whole initiative has documented (`complex instruction -> 1-wide
  complex packet, stall until resume`) — a DIFFERENT failure or count means
  investigate before proceeding.
- Targeted mem-indirect/dst-EA/complex-instruction ported tests (this is
  exactly the area both Slice 3 and its review flagged as historically
  fragile — mem-indirect EA word-indexing bugs, tasks #145/#147/#150-156/
  #178-179): grep the ported corpus for `memind`/`idx`/`full`/`complex`-
  family tests and run them explicitly before the full sweep.
- `ExecuteLockStepSpec` full suite: expect 390/394 (same 4 pre-existing
  failures as this session's standing baseline).
- Full ported test corpus (~870 tests) via
  `tools/fuzz/ported-sweep-parallel.sh`, isolated git worktrees: zero new
  regressions vs. the current baseline (check the most recent progress
  ledger for the exact current fail count/list).
- OOC-synth-only AND the real post-route gate, delta reported explicitly.
  **This slice ALONE is expected to move the design's WNS from -2.062ns to
  somewhere around -1.5 to -1.8ns** (the grounding report's estimate,
  ≈0.32-0.56ns improvement on the frontend family), at which point a
  DIFFERENT family (either the LS/ROB family at -1.779ns, or the
  `ucPendPkt` family at -1.833ns) becomes the new binding constraint. Do
  NOT expect this alone to clear 200MHz — the real accept/reject gate is
  the COMBINED measurement with LS/ROB Lever A (per both grounding
  reports' explicit "zero cell overlap, compounds on WNS" finding). Report
  this task's own delta honestly regardless of whether the combined number
  is available yet.

## Open questions for the plan-writing pass

- Confirm exact live line numbers for `Aligner.scala:151-157` and
  `:214-219` before editing (re-verify, this project's own established
  convention).
- Determine precisely whether the exhaustive proof needs to sweep
  `extW`/`extW2`/`extW3` alongside `op`, or whether `op`-only sweeping
  (with fixed/representative ext words) is provably sufficient — this
  requires reading every `r.simple := True` site in `PredecodeWord.scala`
  and classifying whether its `lenWords` value depends on ext-word content
  (it likely does NOT for most sites, since `lenWords` for a `simple`
  instruction is generally determined by the OPCODE's own EA-length
  arithmetic which itself can depend on ext-word bits like `bdSize`/
  `odPresent` — so this needs real verification, not an assumption either
  way).
