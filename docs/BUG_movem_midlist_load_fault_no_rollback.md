# BUG: `MOVEM.L (An)+` mid-list LOAD fault does not roll back already-loaded registers

**Status**: OPEN, root-caused, NOT fixed (judged architectural — see below).
**Severity**: Low-to-moderate correctness gap in a genuinely rare precise-exception
corner (a MOVEM whose register list spans a page/permission boundary and faults
partway through). Does not affect any currently-passing regression or the boot
path this repo has exercised to date. Found by directed lock-step testing, not
by a real-hardware or fuzz repro.
**Found**: 2026-08-26, task `campaign/movem-midlist-fault`.
**Test**: `ExecuteLockStepSpec.scala`, `"lock-step: MOVEM.L (An)+,D0-D3 LOAD
faults on the 3rd register -- full register rollback (Musashi ground truth)"`
(marked `pendingUntilFixed` — real regression coverage of the gap, kept green
in the suite by ScalaTest's own pending-test convention since this project has
no other expected-failure marker).

## One-line summary

On this core, `MOVEM.L (An)+,<list>` that faults partway through the register
list leaves the registers loaded *before* the fault architecturally visible
(and, separately, `An` correctly stays unchanged). Per this project's own
Musashi reference oracle, real 68040-compatible behavior is a **full rollback**
of every register the faulting MOVEM touched — not just the ones from the
fault point onward.

## Why this surprised the task's own starting assumption

The task that found this explicitly expected the *opposite* result, on the
grounds that MOVEM's per-register transfers are separate ROB entries (confirmed
by `RobPluginSpec`'s `"h0IsMacroLast is True on the trailing uop of a cracked
MOVEM, False on its leading uops"` test) and this core's precise-exception
machinery already gives each uop ordinary per-uop commit/fault semantics. That
reasoning is **correct as far as it goes** — the fault IS delivered with
byte-exact PC/EA/SSW/faultAddr precision (see Evidence below) — but it doesn't
settle whether an EARLIER uop's completed register write should remain
architecturally visible after a LATER uop in the SAME macro faults. That
question was resolved empirically against Musashi, not assumed, per the task's
explicit instruction, and the empirical answer contradicts the general
680x0-lore assumption that "registers loaded before the fault keep their
values."

## Evidence

### Musashi mechanism (ground truth, from source, not just from running the tool)

`tools/musashi/musashi/m68kcpu.c`, main execute loop (~line 1040):

```c
/* Record previous D/A register state (in case of bus error) */
for (i = 15; i >= 0; i--){
    REG_DA_SAVE[i] = REG_DA[i];
}
```

This snapshots **all 16** D/A registers immediately before **every**
instruction dispatch. `tools/musashi/musashi/m68kcpu.h`,
`m68ki_exception_bus_error` (~line 2032) — the SAME path this project's own
68040 MMU-fault format-$7 delivery reuses (see the comment immediately above
it: `/* 68040: format-$7 access-error frame (MAME-matched). ... */`):

```c
for (i = 15; i >= 0; i--){
    REG_DA[i] = REG_DA_SAVE[i];
}
```

unconditionally restores **all 16** registers on ANY bus/access error. This is
stock Musashi's classic address-error-recovery mechanism (predates the 68040
MMU patch), generalized by this project's patch to page faults too. It is not
MOVEM-specific — it is a per-instruction, all-registers snapshot/restore that
applies to whatever the faulting instruction touched.

### Empirical confirmation (step-by-step Musashi trace)

`MOVEM.L (A0)+,D0-D3` with A0=0x2ff8, D2's target (0x3000) on a non-resident
page:

```
[ 9] pc=0x40800036 ... D0=0xdead0000 D1=0xdead1111 D2=0xdead2222 D3=0xdead3333 A0=0x00000000   (before A0 :=0x2ff8)
[10] pc=0x4080003c ... D0=0xdead0000 D1=0xdead1111 D2=0xdead2222 D3=0xdead3333 A0=0x00002ff8   (before MOVEM; the MOVEM's own PC)
[11] pc=0x40800044 ... D0=0xdead0000 D1=0xdead1111 D2=0xdead2222 D3=0xdead3333 A0=0x00002ff8   (AFTER the MOVEM faults -> handler entry)
```

Step 11 is the MOVEM's own post-fault commit step. D0/D1 — which a naive
per-register reading of `M68KMAKE_OP(movem,32,er,pi)`'s C loop would expect to
already hold the loaded values `0x11112222`/`0x33334444` — are back to their
pre-instruction poison values. A0 never moved.

### RTL vs oracle (this task's lock-step run)

Frame delivery (PC/fmtVec/EA/SSW/faultAddr) matches Musashi **exactly**:

```
Musashi: frame PC=0x4080003c fmtVec=0x7008 EA=0x00003000 SSW=0x0505 faultAddr=0x00003000
DUT:     frame PC=0x4080003c fmtVec=0x7008 EA=0x00003000 SSW=0x0505 faultAddr=0x00003000
```

Register state at the fault instant (dumped via a handler that touches only
`A1`/`D7`, never `D0-D3`/`A0`) diverges:

```
Musashi: D0=0xdead0000 D1=0xdead1111 D2=0xdead2222 D3=0xdead3333 A0=0x00002ff8
DUT:     D0=0x11112222 D1=0x33334444 D2=0xdead2222 D3=0xdead3333 A0=0x00002ff8
```

D2/D3 (never transferred) and A0 (the trailing An-update uop never commits,
correctly flushed) match. D0/D1 (transferred, committed, and retired BEFORE
D2's fault reached the ROB head) do not roll back on this RTL.

## Root cause

This ROB retires/frees old phys-reg mappings for each uop **independently and
immediately** as it reaches the ROB head — confirmed directly from
`RobPlugin.scala`'s own comment on `debugMacroCountInc`:

> "Counts COMPLETED macro-instructions, not micro-ops: increments once per
> retiring entry whose payload.last is True. A 5-uop MOVEM retiring across 5
> cycles increments exactly once, on its final uop."

I.e. the intermediate uops of a cracked MOVEM retire one at a time, every
cycle, exactly like any other instruction's single uop — there is no notion of
"this uop belongs to a still-in-flight macro, don't let its retirement become
irrevocable yet." When D2's move-uop later faults and the ROB flushes
everything behind it, D0's and D1's move-uops are long gone from the ROB
(already retired, already freed their old phys-reg mappings) and are
structurally unreachable by ordinary flush recovery, which only restores state
for **not-yet-committed** entries.

## Why this is judged architectural, not a quick fix

Matching Musashi's whole-macro rollback needs a genuinely new mechanism:
either

1. **Defer irrevocable commit** for a macro's internal (non-last) uops until
   the macro's LAST uop (the trailing An-update, or the last move if there is
   no An-base) is ready to retire — i.e. group/atomic commit for however many
   uops a MOVEM cracks into (up to 16 register transfers), holding old
   phys-reg free-list release back for the whole span; or
2. **Explicit fault-time undo** — a MOVEM-wide register snapshot-and-restore
   mechanism mirroring Musashi's own `REG_DA_SAVE`, capturing the pre-macro
   value of every register the mask could touch and re-applying it if any
   later uop in the same macro faults.

Both require new state and new control logic in the ROB's retire/free-list
path — the single most shared, most timing-sensitive commit path in the
machine, touched by every instruction, not just MOVEM. This project's own
prior FMax investigations (see `docs/superpowers/` FMax postmortems) flag ROB
commit/fault-arbitration as an already-delicate critical-path area; a change
here is not something to bolt on casually inside a single-scenario task.
This is a different flavor of "hard" than the standing CAS2/BCD/bitfield
single-instruction-atomicity class (which is hard because there is no
per-sub-access uop boundary at all to hang precise semantics off of) — MOVEM's
per-uop precise *fault delivery* is fully correct and already works. The gap
is specifically that "precise fault delivery per uop" is not sufficient to
reproduce "whole-macro register rollback" without additional machinery, because
retirement here is irrevocable the instant it happens.

## What does NOT need fixing (verified correct)

* Fault delivery precision: PC (start of MOVEM, not mid-list), EA, faultAddr,
  SSW all byte-exact vs Musashi.
* `An` is correctly left unchanged on a LOAD fault (the trailing An-update uop
  is correctly flushed, never committing a stale postincremented value).
* D2/D3 (never reached) correctly stay untouched.
* The mirror **STORE**-direction case (`MOVEM.L D0-D3,-(An)`, 3rd store
  faults) is fully correct on this RTL: already-landed stores (D3, D2 in
  program order) stay landed (memory writes are EU-external, irrevocable
  effects the ROB never needs to "un-commit" — no new mechanism needed), the
  faulting and never-reached stores do not write memory, and `An` rolls back
  to its original value via the SAME (already-correct) "the trailing An-update
  uop never committed" path proven by the LOAD test's A0 assertion. See
  `"lock-step: MOVEM.L D0-D3,-(An) STORE faults on the 3rd store (D1) --
  landed stores stay landed, An rolls back"` in `ExecuteLockStepSpec.scala`
  (passes cleanly).

## A secondary, separate, LOW-CONFIDENCE finding (not asserted strictly)

While building the STORE-direction mirror test, the fault EA/faultAddr for a
predecrement `MOVEM.L` STORE differed from Musashi by exactly 2 bytes
(`0x2ffc` on the RTL vs `0x2ffe` on Musashi, for a fault on the register whose
own computed address is `0x2ffc`). Traced to Musashi's `movem,32,re,pd`
C template (`m68k_in.c`), which is **shared across every CPU type** (68000
through 68040, not gated by `CPU_TYPE_IS_040_PLUS`) and writes each register's
LOW 16-bit half at `ea+2` before the HIGH half at `ea` — a 68000-era, 16-bit-
bus artifact. The 68040 has a 32-bit bus; a real 68040 doing one atomic
32-bit store (which is what this RTL does, and what it reports as the fault
address) is the architecturally sensible model. This looks like an
**oracle-side modeling gap** inherited from Musashi's generic template, not an
RTL bug — the STORE test accepts either `ea` or `ea+2` for this one field and
documents why, rather than asserting a match to a quirk that may itself be
wrong for this CPU type. Flagging for awareness; not acted on.

## Suggested next steps

1. If/when this gap is prioritized: design a MOVEM-macro group-commit or
   fault-time-undo mechanism as a dedicated ROB feature (not a quick patch),
   synth-gated per this project's standing rule (every slice ends with a full
   OOC FMax gate) given the retire/free-list path's known sensitivity.
2. Un-pend the `pendingUntilFixed` LOAD test as part of that fix; it will fail
   loudly (ScalaTest turns an unexpectedly-passing pending test into a hard
   failure) if the fix lands without removing the wrapper, which is the
   correct prompt to update this doc's Status.
3. Separately, if the secondary EA/faultAddr finding above is ever worth
   chasing: check MAME's own 68040 MOVEM implementation (not just Musashi's
   generic template) for how it decomposes the bus transaction, since MAME
   is the explicitly-cited reference for this project's format-$7 model.
