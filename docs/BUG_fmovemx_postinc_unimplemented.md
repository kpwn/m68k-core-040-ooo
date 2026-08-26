# SCOPE GAP: `FMOVEM.X (An)+`/`-(An)` and the store direction are NOT implemented

**Status**: not a regression, not a hidden RTL bug — a documented, confirmed scope
boundary that the `campaign/fmovem-postinc-ring` scenario brief assumed was already
closed. Filed so the next session (or a future re-read of the design doc) does not
re-discover this the hard way.

**Severity**: N/A (missing feature, not incorrect behavior). Every instruction form in
this gap traps deterministically as F-line unimplemented (vector 11) — no silent
corruption, no hang, no wrong answer. Confirmed by two new decode-level regression
tests (see below) and by direct source reading.

## What the campaign brief assumed

The task brief for this scenario stated: *"Per the design spec... `(An)+` postincrement
IS in scope and implemented for both load and store directions, unlike plain MOVEM.L
which strides 4 bytes per register, FMOVEM.X strides 12 bytes per register."*

## What is actually true at this task's HEAD (`campaign/fmovem-postinc-ring`, based on
`748197b5`)

Only **task #241/#246** has landed: the `fmovemxActive` FSM skeleton (the second
instantiation of the shared `RegListWalk` register-list-walk skeleton), **LOAD direction
only** (`FMOVEM.X <ea>,<list>`, ext1 opclass `110`), admitting **exactly two EA modes**:
`(An)` (mode 010) and `(d16,An)` (mode 101).

This is the design doc's own §7 breakdown, **item 1 of 4**:
> 1. FSM skeleton + non-auto EA modes `(An)`, `(d16,An)` + load direction only — proves
>    the core multi-element-multi-phase mechanism works before adding auto-update/
>    store/indexed complexity.
> 2. Store direction + `(An)+`/`-(An)` auto-update.
> 3. `(d8,An,Xn)` indexed EA (scale=0 only) — both directions.
> 4. `(d16,PC)` load-only.

Items 2-4 have **not** landed. `git log --oneline --all | grep -i fmovem` shows only
`ea60a013`/`9d6d762d` ("shared register-list-walk skeleton + FMOVEM.X data-list FSM",
task #241/#246) and the design-doc commits — nothing implementing auto-update, the
store direction, indexed EA, or PC-relative. (Task number `#242` was independently
reused later for an unrelated bug, `memind_wide_disp_dst HANG` — a coincidence in the
task-numbering sequence, not evidence items 2-4 shipped under a different name; no
`fmovemxAnUop`-equivalent An-auto-update µop exists anywhere in
`DecodeStage.scala`.)

## The evidence

1. **Source**: `DecodeStage.scala`'s `s0IsFpGenMemEa` (~line 675) restricts the EA mode
   field to `s0FpGenEaMode === B"3'b010" || === B"3'b101"` — mode 011 `(An)+` and mode
   100 `-(An)` are excluded by construction. The surrounding comment says outright:
   *"postinc/predec/indexed/PC-rel stay on the µcode engine's existing trap path until
   tasks #242-245"*.
2. **Source**: `ucFpStoreEntry`'s dispatch and the existing
   `FpMemLoadSpec.scala` test *"FMOVEM.X list,(A0) (opclass 111, store — task #242,
   still unowned): still traps"* confirm the STORE direction (opclass 111 with a memory
   EA) unconditionally routes to `FP_MEM_TRAP_ENTRY` (vector 11).
3. **New regression tests** (this task, `FmovemxDataListDecodeSpec.scala`), run and
   GREEN:
   - `"FMOVEM.X (An)+,FP0-FP7 (EA mode 011, load direction -- postinc NOT YET
     implemented) still traps"`
   - `"FMOVEM.X -(An),FP0-FP7 (EA mode 100, predecrement NOT YET implemented) still
     traps"`

   Both assert `faulted && faultVector == 11` and that the data-list engine's
   `[LOAD x3 chunks]` shape was NOT reached. Command:
   `sbt "testOnly m68k040.decode.FmovemxDataListDecodeSpec"` → **10/10 passed** (8
   pre-existing + these 2 new).

## Why this blocked the scenario as originally scoped

The scenario wanted `FMOVEM.X (An)+,FP0-FP7` with a misaligned base, driven end to end
through lock-step, to stress the aligned-load split ring with a *rotating* multi-split
pattern (12-byte stride vs. a 16-byte line, unlike plain `MOVEM.L`'s fixed-phase 4-byte
stride). That specific opcode form traps immediately — it never reaches the LS EU's
split-load ring at all, so there is no ring behavior to observe or break through it
today.

## What was done instead

The ring-stress *mechanism* the campaign cares about — `fmovemxOff` incrementing by 12
bytes per element, independent of EA mode — is fully exercisable through the
**already-implemented** `(d16,An)` form. A misaligned `(d16,An)` base drives the exact
same rotating-phase-across-elements multi-split pattern `(An)+` would have. See
`FpuLockStepSpec.scala`'s new test *"lock-step: FMOVEM.X (d16,An),FP0-FP7 -- misaligned
base drives rotating-phase multi-split ring pressure"* for the substitute test (GREEN,
6 genuine split-pair pushes observed live via `alignedEnqSplit`, all 8 FPn round-tripped
bit-exact). That test's own header comment also records a second, independent finding:
this project's own vendored Musashi (`tools/musashi/musashi/m68kfpu.c`,
`READ_EA_FPE` case `imode==2`) does not advance the address for `(An)` across a
multi-register list, so `(An)` itself would not even have been a valid lock-step oracle
for this stress pattern — `(d16,An)` was the correct choice independent of the
`(An)+`-unavailability finding above.

## Recommendation

When design-doc §7 items 2-4 (task #242-245, under whatever task numbers they
eventually land as) implement `(An)+`/`-(An)` auto-update, re-run the rotating-phase
stress directly against the real postincrement form and retire this doc — the
`(d16,An)` substitute test should stay regardless (it is a real, valuable in-scope
regression on its own), but the original `(An)+` scenario becomes directly testable at
that point and should be added alongside it, including the `An == base + 96` final
auto-update check this doc's substitute could not perform.
