# BUG: `FMOVEM.X <ea>,<list>` (data-register-list LOAD) corrupts the ROB commit-PC

**Status**: OPEN. Sim-only finding; not run on hardware. Found 2026-08-26 while
building a directed test for a *different*, unrelated scenario
(`campaign/fmovem-postinc-ring`'s aligned-load split-ring rotating-phase stress) —
this bug blocks that scenario's own test from ever reaching the ring behavior it was
written to exercise, so it is reported first, separately, and ahead of that
scenario's own (still-unresolved) verification. **UPDATE (same day, follow-up
session)**: the originally-suspected mechanism (`RobPlugin.commitPc0`'s
`retireAlone` mux) has been isolated with two whitebox tests and **EXONERATED** —
it retires this uop shape correctly in every case tested, including under
back-to-back retire pressure. The real defect is confirmed to live upstream of the
ROB (predecode length computation / the `fmovemxActive` FSM's interaction with
rename allocation / `DivEuPlugin`'s FP-completion robId tracking — none pinned yet).
See "Root-cause leads" below for the full detail.

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

## Root-cause leads

### UPDATE 2026-08-26: `RobPlugin.scala`'s `commitPc0`/`retireAlone` mux is EXONERATED

A follow-up session added two `RobPlugin`-level whitebox tests to
`RobPluginSpec.scala` (search `"ROB ISOLATION"`) per this doc's own "Suggested next
steps" §1, and they **both pass**, not just as static reasoning but as an actually-run
sim result:

1. `"ROB ISOLATION (bug doc): FMOVEM.X-issue-row-shaped uop ... retires with
   commitPc0 == predNextPc"` — allocates a synthetic `RenamedUop` shaped exactly like
   `fmovemxIssueUop` (`isBranch=False`, `pdstValid=False`, i.e. no int dest) and
   completes it via completion **port 5** (the real full-core FP lane,
   `rob.logic.completion(5).valid := divEu.fpCompletion.valid` in
   `FullCoreSynth.scala`) in total isolation. `commitPc0` correctly resolves to
   `predNextPc`. PASSES.
2. `"ROB ISOLATION (bug doc, repro-C shape): the instruction retiring immediately
   after an FMOVEM.X-issue-row-shaped entry gets its OWN predNextPc"` — allocates
   TWO entries (an FMOVEM.X-issue-row-shaped one at robId0, then an ordinary
   int-writing MOVE at robId1), completes robId0 via port 5 and confirms its commit
   pc, THEN completes robId1 via port 0 (the ordinary int lane) and confirms its
   commit pc is its own `predNextPc`, not the just-retired FP entry's. This directly
   targets repro C's symptom ("the VERY NEXT retiring instruction's commit-pc ...
   suspiciously identical to the FMOVEM's own just-retired commit-pc"). PASSES.

This means, confirmed (not reasoned) by direct sim evidence:
- `p0.retireAlone := u.isBranch` DOES correctly evaluate `False` for this exact uop
  shape and completion-port combination — it never reads `nextPcRd0`/`nextPcMem`.
- `p.predNextPc := u.nextPc` (alloc-time, unconditional — no gating on
  `dstValid`/`pdstValid`/`cluster`/completion-port-index of any kind) is written and
  read back correctly, per-entry, with no cross-entry bleed even under immediate
  back-to-back retire pressure (robId1 never reads robId0's stale `predNextPc`).
- Completing via completion port 5 specifically vs. any other port makes no
  observable difference to `commitPc0`/`retireAlone`/`predNextPc` — `completes(robId)
  := True` is set identically by every one of the 6 ports
  (`for (c <- completion) when(c.valid) { completes(c.payload) := True }`), and none
  of `commitPc0`'s inputs read which port fired.

**Conclusion: `RobPlugin.scala`'s retire/commit-pc logic itself is NOT the bug.** The
original suspicion (this section, pre-2026-08-26) was a plausible but WRONG lead —
the mux reads correctly for this uop shape in every case this isolated harness can
construct. The defect must be upstream of the ROB: either (a) the value written into
`predNextPc`/`pc` at allocation is ALREADY wrong by the time it reaches the ROB's
alloc-write (i.e. `fmovemxNextPc`/`fxNextPc` itself, or something corrupting it
between `DecodeStage` and the ROB's alloc port), or (b) the uop lands in the WRONG
physical ROB row (an allocation-index/aliasing bug upstream of `payload.write`, not
inside `RobPlugin`'s own retire-read logic), or (c) — given repro A's "a7 is ALSO
wrong despite nothing in the program touching A7" observation — genuinely wrong
EXECUTION for `(An)` mode specifically (a real control-flow/redirect defect, not a
pure observability/framing bug). On point (c): the lock-step harness's `a7` field
(`ExecuteLockStepSpec`'s `c.a7`) is sourced from `RobPlugin`'s `exc.ss.a7` (the live
architectural A7 tracked by the exception/supervisor-state unit), a COMPLETELY
SEPARATE piece of state from `commitPc0`/`predNextPc` — they do not share storage or
a read path. Two unrelated fields being simultaneously wrong is therefore NOT
explained by a single mis-read inside `RobPlugin`; it is much more consistent with
the CPU genuinely having executed something other than the intended instruction
stream after (or around) the FMOVEM.X `(An)` macro — i.e. a real frontend/decode/
rename-level defect specific to `(An)`'s zero `fxBaseDisp`, not a retire-time
observability artifact. This also means `commitPc0` is very unlikely to be what
drives the CPU's actual next-fetch address for a non-`retireAlone` (non-branch) entry
like FMOVEM.X's issue row — `commitPc0` only feeds `debugLivePcReg`/the commit TRACE,
not a real redirect, since `flushPcReg`/`branchRedirect` are gated on
`p0.retireAlone` (False here) — so a wrong `commitPc0` for this uop, by itself,
cannot be *causing* real wrong execution; at most it would be a downstream SYMPTOM of
the same upstream corruption that produces the a7 corruption.

**Genuinely still open** (not reached by this or the prior session):
- What actually corrupts `predNextPc`/`pc` (or misroutes the ROB row) for FMOVEM.X's
  issue-row uop specifically under `(An)` mode — candidates, none checked yet:
  - `fxNextPc = fxPc + (fxEntryPkt.lenWords << 1)` (`DecodeStage.scala`, FMOVEM.X
    entry-detection block) depends on `fxEntryPkt.lenWords`, the GENERIC
    predecode-computed instruction length. Confirm predecode's EA-length table
    entry for FMOVEM.X's two admitted modes (010/101) actually reports the right
    word count for `(An)` (2 words: opword+ext1, no disp16) vs `(d16,An)` (3 words) —
    this session did NOT verify predecode's own length computation for this specific
    opcode class, only that `RobPlugin` correctly threads through whatever length it
    is given.
  - Whether the `fmovemxActive` FSM's push (`pushProduced` in `elsewhen(fmovemxActive)`,
    `DecodeStage.scala`) can, specifically for `(An)`'s 1-element/4-cycle-total case,
    interact badly with `RenameStage`'s allocation-index bookkeeping (a genuine
    robId mis-assignment upstream of `RobPlugin.payload.write`, not inside it).
  - `DivEuPlugin`'s FP completion-robId capture (`fpCompRobId := ctx.robId`,
    `DivEuPlugin.scala` ~line 1185) and whether the fixed CPLX issue pipe can
    associate a stale/wrong `robId` with the FMOVEM.X issue-row's completion —
    unverified this session; would explain "reads garbage" without needing
    `RobPlugin` itself to be at fault, since `completion(5).payload` (the robId
    completing) comes from here, not from `RobPlugin`.
  - Whether `(An)`'s zero `fxBaseDisp` interacts with a SHARED signal/mux keyed off
    EA mode somewhere in `LsEuPlugin`'s AGU for the 3 chunk loads, in a way that
    could cause a genuine wrong-path flush/redirect (which WOULD explain real
    execution divergence + a7 corruption) that this session did not trace.

## Why this was flagged, not fixed

Two whitebox tests (above) successfully isolated and EXONERATED the originally-
suspected mechanism (`RobPlugin.scala`'s `retireAlone`/`commitPc0` mux) — a genuine,
confirmed narrowing of the bug, not just more correlation. But that isolation also
means the actual defect lives somewhere upstream of the ROB (predecode length
computation, the `fmovemxActive` FSM's allocation-index interaction with
`RenameStage`, or `DivEuPlugin`'s FP completion robId tracking — see the open list
above), each of which is its own substantial subsystem this session did not have
budget to trace to a confirmed mechanism. Guessing a fix in any of those areas without
first repeating this session's isolation discipline (a targeted whitebox test that
pins the exact wrong value/index BEFORE writing a fix) risks the same wrong-guess
outcome this doc originally warned against for `RobPlugin.scala` — except now in
several candidate subsystems instead of one, with less prior narrowing work done in
any of them. Still flagged, not fixed.

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

1. ~~Add a `RobPlugin`-level whitebox test...~~ **DONE 2026-08-26** — see the "UPDATE"
   section above. Result: `RobPlugin.scala` EXONERATED, not confirmed as the bug.
2. Next isolation target: predecode's length computation for FMOVEM.X's two admitted
   EA modes (`fxEntryPkt.lenWords`, consumed by `DecodeStage.scala`'s `fxNextPc`).
   A cheap, targeted test would decode both `(An)` and `(d16,An)` FMOVEM.X opwords
   through predecode ALONE (no ROB, no rename — mirrors `FmovemxDataListDecodeSpec`'s
   own existing harness style) and assert the emitted length in words for each,
   independent of everything downstream. If this comes back correct too, move to
   `DivEuPlugin`'s `fpCompRobId` capture and the `fmovemxActive` FSM's interaction
   with `RenameStage`'s allocation index (see the open bullet list above) — likely
   needs a waveform trace of a real repro-A run (`(An)` single-element) rather than
   another synthetic whitebox, since the defect (if in FSM/rename interaction) may
   depend on exact multi-cycle timing this style of isolated test cannot easily
   reproduce without re-modeling the FSM.
3. Once isolated, re-run repro A and repro C's underlying programs to confirm the fix
   closes BOTH (they may turn out to be the same root cause manifesting two ways, or
   two separate bugs needing two fixes — do not assume either without re-testing both).
4. Re-enable the two `ignore`d tests in `FpuLockStepSpec.scala`
   (`"[KNOWN BUG...] FMOVEM.X (An),FP0 single-element..."` and `"[BLOCKED...] lock-step:
   FMOVEM.X (d16,An),FP0-FP7 -- misaligned base..."`) and confirm both go green.
5. Re-run the full `ExecuteLockStepSpec`/`FpuLockStepSpec` suite (not just `test-fast`,
   which excludes every `VerilatorTest`-tagged suite including these) to confirm no
   collateral regression in the branch-retire path the fix will necessarily touch.

---

## CORRECTION NOTE (2026-09-03) — root-cause lead (c) rests on a premise that is FALSE

**Status: NEEDS RE-DERIVATION. Not adjudicated — this note does not overturn the bug, it
invalidates one argument used to characterise it.**

Added by the fuzz-to-zero-divergences campaign
(`docs/superpowers/campaigns/2026-09-03-fuzz-to-zero-divergences.md`, "Round 2 — cluster C").

### What is wrong

The "Root-cause leads" section, point **(c)** (≈lines 179-190), argues that repro A's
*"`a7` is ALSO wrong despite nothing in the program touching A7"* observation points at
**genuinely wrong execution** rather than an observability artifact. The stated reasoning is:

> "the lock-step harness's `a7` field (`ExecuteLockStepSpec`'s `c.a7`) is sourced from
> `RobPlugin`'s `exc.ss.a7` (the live architectural A7 …), a COMPLETELY SEPARATE piece of
> state from `commitPc0`/`predNextPc` — they do not share storage or a read path. Two
> unrelated fields being simultaneously wrong is therefore NOT explained by a single
> mis-read inside `RobPlugin`."

**"Separate storage" is true. "Live architectural A7" is not.** `exc.ss.a7` is a
**≥2-commit-cycle-lagged shadow**, and the lock-step harness replays that lag through a
buggy resync, which can make `a7` read wrong on an instruction that never touches A7 —
with no wrong execution whatsoever. Verified against current RTL (not comments):

- `ExceptionUnit.scala:734` — `ss.writeA7.valid := True` is driven **unconditionally, every
  cycle**, from `committedA7In` (the int-PRF readback at `committedPhysA7`,
  `forceNoBypass`). So `ss.a7` mirrors **every** A7 write, not just exception/RTE/boot.
- `ExceptionUnit.scala:727-733` documents the lag inline: *"the ACTIVE bank tracks A7 with
  ~1-2 cycle lag."*
- `SystemState.scala:70` — `val a7 = Mux(s, supBank, usp)`, a mux of those lagging banks.
- `RobPlugin.scala:2629` — `obs(0).a7 := RegNext(exc.ss.a7)` adds another cycle.
- `WhiteboxCapture.scala:110` force-resyncs the reconstructed A7 on any **value edge** of
  that lagged sample, and does so **before** the OoO writeback fold at `:116` — so a
  commit record that carries its own arch-15 writeback is immune, while any instruction
  that does **not** write A7 is exposed to a late replay of an older A7 value.

Net effect: after **two A7-changing retirements in close succession**, the *next*
non-A7-writing instruction can report a stale A7 and then self-correct one step later.
That is exactly the shape of the "a7 is also wrong" observation.

### Why this matters for this bug

FMOVEM.X `(An)` sits directly downstream of stack/`A7` traffic in the campaign scenario. If
repro A's instruction stream moves A7 twice near the failure point, the `a7` half of the
"two unrelated fields are simultaneously wrong" argument is **fully explained by the
harness artifact**, and lead (c) loses its principal supporting evidence. The `commitPc0`
half of the observation is untouched by this note and still stands on its own.

### What to do before relying on lead (c)

Re-derive the `a7` evidence with a source independent of `ss.a7`. The cheapest way needs no
instrumentation: add an A7-**reading** instruction (`move.l %sp,%d0`) near the failure
point. `D0` is compared through the `archReg` path, sourced from the EU writeback
observation (`WhiteboxCapture.onWb`), which does not touch `ss.a7` at all. If `D0` holds the
correct A7 while the `a7` field reads wrong, the architectural A7 was never wrong and lead
(c)'s premise is void.

*Filed by cross-check, not by re-running this bug's reproducer. Someone owning this bug
should confirm before acting either way.*
