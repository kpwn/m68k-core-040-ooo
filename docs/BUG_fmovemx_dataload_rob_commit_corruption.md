# BUG: `FMOVEM.X <ea>,<list>` (data-register-list LOAD) corrupts the ROB commit-PC

**Status**: OPEN, root-caused to a specific mechanism (`RobPlugin.commitPc0`'s
`retireAlone` mux) but NOT pinned to a single faulty line. Sim-only finding; not run
on hardware. Found 2026-08-26 while building a directed test for a *different*,
unrelated scenario (`campaign/fmovem-postinc-ring`'s aligned-load split-ring
rotating-phase stress) — this bug blocks that scenario's own test from ever reaching
the ring behavior it was written to exercise, so it is reported first, separately, and
ahead of that scenario's own (still-unresolved) verification.

**Severity**: HIGH for correctness (any real program using `FMOVEM.X <ea>,<list>` — the
FPSP register-save/restore idiom is exactly this — would corrupt its own following
instruction's commit framing and/or read garbage architectural state), LOW for
immediate blast radius today (the feature landed very recently, task #241/#246, and
per `docs/BUG_fmovemx_postinc_unimplemented.md` has almost no addressing-mode coverage
yet — `(An)`/`(d16,An)` only, load direction only — so real-world exposure is still
narrow, but growing as more of the design doc's §7 breakdown lands).

## Why this was never caught

`task #241/#246`'s own verification (`FmovemxDataListDecodeSpec.scala`,
`FpMemLoadSpec.scala`, `MicroOpAssemblerSpec.scala`) is **decode-level only** — it
drives raw opcode words through fetch→align→decode and inspects the emitted µop
*stream shape* (chunk addresses, register mapping, drop/keep flags), using a
lightweight `Dut` that has no ROB, no rename, no execute units, no lock-step. **No
test ever pushed an FMOVEM.X data-list µop stream through a real `FullCoreDut` and
checked its ROB commit against Musashi** before this session. This is exactly the gap
the campaign brief called out ("confirm no existing test drives ... through real
execution/lock-step, as opposed to just decode-level uop-shape checks") — checking it
directly, for the first time, found this.

## Reproduction

All three programs run through `ExecuteLockStepSpec.runLockStep` (Musashi oracle).
See `FpuLockStepSpec.scala`'s new block just above the ring-stress test
(`campaign/fmovem-postinc-ring` header comment) for the exact, checked-in (but
`ignore`d) test code.

### Repro A — `(An)` mode, single-register list: wholesale garbage commit

```
movea.l #0x3000,%a1
move.l  #0x3fff0000,(%a1)
move.l  #0x11223344,(4,%a1)
move.l  #0xaabbccdd,(8,%a1)
fmovem.x (%a1),%fp0            ; opword 0xF211, ext1 0xD080
move.l  %a1,%d7
```

Command: `sbt "testOnly m68k040.lockstep.FpuLockStepSpec -- -z \"(An),FP0 single\""`
(the checked-in test is `ignore`d; remove `ignore`→`test` locally to reproduce).

Observed (via `CR_DEBUG=1`):
```
idx 3 dut pc=0x4080001c a7=0x00100000 | orc pc=0x4080001c a7=0x00100000   (matches)
idx 4 dut pc=0xa9fdd5fd a7=0x000ffff4 | orc pc=0x40800020 a7=0x00100000   (WRONG)
Divergence(4, pc: dut=0xa9fdd5fd oracle=0x40800020)
```

idx4 is the `fmovem.x` instruction's own commit — its expected `pc` (nextPc) is
`0x40800020` (a clean 4-byte instruction: opword + ext1, no EA extension word for
`(An)`), matching the oracle exactly by hand computation. The DUT instead reports
`0xa9fdd5fd` — **and `a7` is ALSO wrong** (`0x000ffff4` vs. the correct, untouched
`0x00100000`) despite nothing in this program ever touching A7/SP. Two unrelated
fields being simultaneously wrong, with values that don't look like any plausible
off-by-N arithmetic, is the signature of reading an **uninitialised/wrong ROB
row/register** in simulation (X-propagation), not a simple framing miscalculation.

### Repro B — `(d16,An)` mode, single-register list: passes clean

Identical shape, `(d16,An)` instead of `(An)`:
```
movea.l #0x3000,%a1
move.l  #0x3fff0000,(2,%a1)
move.l  #0x11223344,(6,%a1)
move.l  #0xaabbccdd,(10,%a1)
fmovem.x (2,%a1),%fp0          ; opword 0xF229, ext1 0xD080, disp16 0x0002
move.l  %a1,%d7
```
**PASSES** — `sbt "testOnly m68k040.lockstep.FpuLockStepSpec -- -z \"(d16,An),FP0 single\""`
→ 1/1 green. This is the checked-in, real (non-ignored) regression `"lock-step:
FMOVEM.X (d16,An),FP0 single-element then a trailing kept instruction"`.

### Repro C — `(d16,An)` mode, FULL 8-register list: trailing-instruction off-by-2

Same `(d16,An)` EA family as repro B, but a genuine multi-cycle FSM drain (8 elements
× 4 sub-phases = 32 cycles) instead of a single 4-cycle element:
```
movea.l #0x3000,%a1
<24 move.l #imm,(disp,%a1) stores seeding 8 x 12-byte Extended values>
fmovem.x (2,%a1),%fp0-%fp7
move.l  %a1,%d7
```
(Full program: the `ignore`d "rotating-phase multi-split ring pressure" test in
`FpuLockStepSpec.scala`.)

Observed:
```
idx25 dut pc=0x408000cc | orc pc=0x408000cc   (FMOVEM's OWN commit -- CORRECT)
idx26 dut pc=0x408000cc | orc pc=0x408000ce   (the FOLLOWING move.l -- WRONG, short by 2)
Divergence(26, pc: dut=0x408000cc oracle=0x408000ce)
```

Here the FMOVEM macro's own commit is exactly right, but the **very next** retiring
instruction's own commit-pc is short by one word (as if it had zero length), and its
value is suspiciously identical to the FMOVEM's own just-retired commit-pc — consistent
with some downstream consumer momentarily re-reading/re-using the FMOVEM's `nextPc`
for the next entry too, rather than that entry's own.

### Reconciling A vs. B vs. C

Three genuinely different symptoms from three closely related programs:

| | EA mode | element count | symptom |
|---|---|---|---|
| A | `(An)` | 1 | FMOVEM's own commit: wholesale garbage (pc AND a7) |
| B | `(d16,An)` | 1 | clean, no divergence |
| C | `(d16,An)` | 8 | FMOVEM's own commit fine; the NEXT instruction's commit-pc short by 2 |

This is not obviously one bug with one trigger. `(An)` (repro A) fails even in the
smallest possible case; `(d16,An)` only fails once the FSM runs a genuine multi-cycle,
multi-element drain (repro C) but is fine for the trivial 1-element case (repro B).
Whether these are the same underlying defect surfacing differently, or two separate
defects, was **not resolved** — see "Not yet done" below.

## Root-cause leads (not confirmed to a single line)

`RobPlugin.scala`'s commit-pc mux:
```scala
val commitPc0 = Mux(p0.retireAlone, nextPcRd0, p0.predNextPc)
```
- `p0.predNextPc` is populated straight from the allocated uop's own `.nextPc` field
  (`p.predNextPc := u.nextPc`, line ~830) — for FMOVEM.X's issue-row uop this is
  `fmovemxNextPc`, which is provably computed correctly (matches oracle exactly at
  entry — see repro C's idx25).
- `p0.retireAlone` is set `:= u.isBranch` at allocation (line ~851). FMOVEM.X's
  issue-row uop explicitly sets `u.isBranch := False`
  (`MicroOpAssembler.fmovemxIssueUop`), so `retireAlone` should be `False`, which
  selects `predNextPc` (the correct path) — NOT `nextPcRd0`.
- `nextPcRd0` reads `nextPcMem`, a `Mem` with **exactly one writer**
  (`branchCompletion`, per its own doc comment: "Single write port into nextPcMem
  (ROB-fold Slice B) — the ONLY writer"). Any ROB row that is read via this path
  without ever having been written by a branch completion reads **garbage** (an
  uninitialised `Mem` row in simulation) — which matches repro A's symptom exactly
  (wholesale garbage, multiple fields, not a clean off-by-N).

**The open question this doc does NOT answer**: why would `p0.retireAlone` (or
whatever gates the `nextPcRd0` read) ever evaluate `True`/select the wrong source for
an `isBranch=False` FMOVEM.X issue-row uop, especially only for `(An)` mode and not
`(d16,An)`? Candidates not yet checked:
- A rename/allocation-time field getting mixed up specifically for the "direct push,
  bypassing `AssembledUops`" path the `fmovemxActive` FSM uses (shared with `movemActive`
  and the µcode sequencer, all three of which DO have their own passing lock-step
  coverage — so the shared mechanism itself is not obviously broken; something about
  the FP-specific combination — `dstValid=False` + FP-lane-only completion port
  `divEu.fpCompletion`/`rob.logic.completion(5)`, which no OTHER "direct push" FSM's
  kept-commit µop combines — is the more likely differentiator).
- Whether `(An)`'s zero `fxBaseDisp` (vs. `(d16,An)`'s real displacement) interacts
  with some SHARED register/mux keyed off EA mode elsewhere in the allocation or
  ROB-write path (not found by this session's search).
- Repro C's "next instruction inherits the just-retired commit-pc" shape suggests a
  possible one-cycle-stale read of SOME register (not necessarily `nextPcMem`/
  `retireAlone` — could be a completely separate mechanism from repro A's) that only
  manifests after a genuinely multi-cycle FSM drain, not a single-shot one.

## Why this was flagged, not fixed

`RobPlugin.scala` is large, deeply pipelined, and explicitly documented elsewhere in
this codebase as FMax-critical (retire/commit-PC framing sits on the same shared
`nextPcMem`/`branchTrainMem`/`mispredictStore` LUT-reduction fold as branch
prediction training). Patching the `retireAlone`/`commitPc0` mux based on the
correlational evidence above — without having actually traced why `retireAlone`
selects the wrong path only for these specific FMOVEM.X cases — risks either not
fixing it, or silently breaking branch-family retire (which shares the exact same mux
and depends on `retireAlone` reading `nextPcRd0` correctly for **branches**). This is
exactly the kind of fix that needs a proper trace-driven investigation (waveform or a
dedicated `RobPlugin`-level whitebox test isolating `retireAlone`/`commitPc0` for a
synthetic non-branch, no-int-write, FP-lane-completion uop), not a one-line guess
under time pressure.

## Impact on the `campaign/fmovem-postinc-ring` scenario

The scenario's own directed test (`"lock-step: FMOVEM.X (d16,An),FP0-FP7 -- misaligned
base drives rotating-phase multi-split ring pressure"`, `FpuLockStepSpec.scala`) is
blocked by repro C: even with a correct rotating-phase-split-count assertion and
correct hand-verified line-crossing arithmetic, the program cannot pass lock-step
end to end because of this bug, unrelated to the ring itself. The test is checked in,
fully written, and `ignore`d — re-enable it once this bug is fixed; no changes to the
test itself should be needed (its own assertions about the split ring and per-FPn
correctness are independent of this bug and were never actually reached/verified as
a result of the divergence firing first).

## Suggested next steps

1. Add a `RobPlugin`-level whitebox test (mirroring `RobFaultSpec.scala`/
   `RobPluginSpec.scala`'s own low-level harness style, not a full lock-step) that
   allocates a synthetic uop shaped exactly like FMOVEM.X's issue row (`isBranch=False`,
   `dstValid=False`, completes via a **non-integer** completion port) and directly
   inspects `retireAlone`/`commitPc0` at retire — this isolates the mechanism without
   the full FullCoreDut/Musashi machinery each iteration currently costs (~15-50s
   compile+sim per run in this investigation).
2. Once isolated, re-run repro A and repro C's underlying programs to confirm the fix
   closes BOTH (they may turn out to be the same root cause manifesting two ways, or
   two separate bugs needing two fixes — do not assume either without re-testing both).
3. Re-enable the two `ignore`d tests in `FpuLockStepSpec.scala`
   (`"[KNOWN BUG...] FMOVEM.X (An),FP0 single-element..."` and `"[BLOCKED...] lock-step:
   FMOVEM.X (d16,An),FP0-FP7 -- misaligned base..."`) and confirm both go green.
4. Re-run the full `ExecuteLockStepSpec`/`FpuLockStepSpec` suite (not just `test-fast`,
   which excludes every `VerilatorTest`-tagged suite including these) to confirm no
   collateral regression in the branch-retire path the fix will necessarily touch.
