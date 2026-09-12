# Decode stack: remove per-op-family checks and hand-built constructions

Owner goal: *"Fix the CPU decode stack such that we no longer have random
per-op-family checks and assembler overrides / by-hand constructions; the core
needs to be elegant and without redundant code and weird side paths."*

## The north star

> `OperationDecoder` produces a COMPLETE description of the instruction -- op,
> operands INCLUDING effective-address typing, size, legality and the shape it
> must be built in. `MicroOpAssembler` consumes that description and emits
> uops. **It never re-matches the opword.**

Everything below follows from that one rule.

## The measured debt

| symptom | count |
|---|---|
| `isXxx` family predicates in `MicroOpAssembler` | 35 |
| ...of which **re-match the opword** (`op(15 downto 6) === ...`) | **21** |
| ...of which are spec-driven (`spec.op === DecOp.X`) -- the RIGHT pattern | 12 |
| negated family guards in the single `bad` expression | **28** |
| memory-indirect routing: hand-listed families (slot 1 / slot 0) | 12 / 11 |
| direct uop field writes (hand construction) | 72 |
| decode stack size | 15,586 lines (`MicroOpAssembler` 4,493) |

The 12 spec-driven predicates matter: the correct pattern is **already in this
file and already works**. This is not a rewrite into something unproven, it is
finishing a conversion that was started and left half-done.

## Why this is correctness work, not tidying

The family lists have produced **four** silent-wrong-answer bugs, every one
found by fuzzing rather than by construction (`DecodeStage`'s own note: tasks
#144/#145, #150, #152, TAS 2026-09-03). Two families -- `Scc <ea>` and the
line-E memory shift -- are missing from the routing list *today* and execute
with a garbage address. The `bad` expression's 28 exclusions are the same
hazard in a different shape: each says "this family is hand-built later, so the
generic rules do not apply", and forgetting one silently illegalises or
silently permits an instruction.

FSAVE/FRESTORE was the same story a level down: the decoder admitted two EA
modes out of eight, and real Mac OS code crashed on `FRESTORE d16(An)`.

## Slices, in dependency order

**Slice 1 -- the decoder owns the EA.** (IN FLIGHT) Type the effective address
in `OpSpec` for every instruction that has one, including those the assembler
hand-builds (`Scc`, `LEA`, `PEA`, `JMP`, `JSR`). Then the memory-indirect gate
becomes two terms keyed on `EaClass.MEMINDIRECT` with no family names, and the
12/11 lists delete. Declaring "my destination is this EA" does not require the
decoder to own the rest of the instruction.

**Slice 2 -- the decoder owns the FORM.** Replace the 21 opword re-matches with
a spec field. The assembler asks *what shape is this* rather than *what bits are
these*. Missing families: `JMP`, `JSR`, `LEA`, `PEA`, `Scc`, `RTE`, `RTS`,
`RTR`, `RTD`, `LINK`, `UNLK`, `TRAP`, `TRAPV`, `TRAPcc`, `BSR`, `EXG`, `DIV.L`,
`MUL.L`, the FP condition family, `MOVE from SR/CCR`, `MOVE to CCR`.

> **CONSTRAINT -- do NOT express the form as new `DecOp` elements.**
>
> `DecodedUop.op` is 6 bits and is FMax-critical. Measured, post-route
> (`xcku5p-ffvb676-2` @4.000ns, checkpoint `3cba17f`): a single extra test
> `op === SHIFT || op === BITFIELD` in `IssueQueuePlugin`'s scoreboard-clear
> cone dragged the `op` MuxOH out of the select cone and into a second cone,
> making that family **the design's WNS holder -- 9 of the 10 worst paths,
> -1.699ns**. Removing the term measured -1.518ns. The `FPU` DecOp exists as ONE
> element with a `fpuOp` sub-kind for exactly this reason, and the enum comment
> says so.
>
> The form therefore belongs on **`OpSpec`**, not on `DecodedUop.op`. OpSpec is
> DECODE-TIME: the assembler reads it and it never propagates into the issue
> queue, so it adds nothing to the `op` cone. This is also why the existing 12
> spec-driven predicates are free while the 21 opword re-matches are not merely
> ugly -- they are decode-time work done in the wrong place, not an execute-time
> cost.

**Slice 3 -- legality decided once.** With Slices 1 and 2 done, `bad` collapses:
the decoder knows whether an instruction is legal, because it knows its form and
its EA. The 28 exclusions exist only because the assembler re-derives a verdict
the decoder should have supplied. Target: `spec.illegal` plus the EA checks,
with no family names.

**Slice 4 -- retire the hand-built constructions.** The 72 direct uop field
writes are the residue. Some are legitimate (a macro's per-element uops are
genuinely constructed); the ones that exist to OVERRIDE a spec the decoder got
wrong are not. Separate the two and fix the decoder in each case.

**Slice 5 -- narrow what the issue queue carries.** (Owner question: *"why does
the issue queue need so much info and not a narrower DecodedUop"*.)

`DecodedUop` has **89 fields**. The IQ already splits its payload -- `IqHot()` is
registered and sits in the critical cones, while `coldWay0/1 = Mem(RenamedUop(),
64)` holds the bulk in a Mem. The split is right; `op` is on the WRONG SIDE of
it.

`IqHot` carries dependency state (`psrcA/B/C`, `pNzvcSrc`, `pXSrc`,
`pFpSrcA/B`, `pFpccSrc`, `pdst`, `pNzvcDst`) -- genuinely needed for wakeup --
plus routing (`cluster`, `memOp`, `isBranch`), plus **`op`**, a 6-bit `DecOp`.
And `op` exists in the hot payload for exactly FIVE tests:

```scala
isAluSlow   = op === SHIFT || op === BITFIELD
isPackUnpk  = op === PACK  || op === UNPK
isBitfield  = op === BITFIELD
isBfResolve = op === BFRESOLVE
(op === DIV || op === DIVREM)
```

Every one is a LATENCY/PORT-CLASS question -- *how should this be scheduled* --
not *what operation is this*. The IQ never needs the operation; the EU reads
that at issue. `cluster` in the same bundle already demonstrates the correct
shape.

**The change:** compute a narrow scheduling class once (at decode or rename) --
something like `IqClass {FAST, SLOW_ALU, BITFIELD, BFRESOLVE, PACKUNPK, DIV}`,
3 bits -- put THAT in `IqHot`, and move `op` to the cold `Mem`. The five MuxOH
comparisons become field tests, and the `op` MuxOH leaves the IQ's cones
entirely.

That makes the -1.699ns WNS family **structurally impossible** rather than
avoided by comment. The existing mitigation is a comment warning future readers
not to re-add a term -- which is exactly the "remember not to" mechanism this
whole goal is trying to delete. It also removes the constraint that currently
shapes Slice 2 (see the CONSTRAINT box above): once `op` is not in the hot
payload, its width stops being FMax-critical.

**Gate:** this one is FMax-sensitive by nature, so it needs a full-core synth
gate before/after, not just fuzz. Independent of Slices 1-4 -- it touches
`IqContext.scala`/`IssueQueuePlugin.scala`, not the decode files -- so it can
proceed in parallel.

## Rules for every slice

1. **Fuzz, do not just run the suite.** Fuzz caught all four prior instances of
   this bug class; the suite caught none of them.
2. **A silent miss is worse than the drift it replaces.** Any generic rule must
   be proven to cover what the list covered -- by construction, not by a green
   run. The corpus does not exercise memory-indirect `Scc` at all, so a
   regression there would be invisible.
3. **Only the strictly necessary stays per-op.** Legitimate residue exists --
   e.g. the line-0 immediate family whose EA extension words are SHIFTED, so its
   EA must be decoded from a different word vector. That is per-op because its
   ENCODING differs, not because its opcode does. Document each survivor with
   that justification or delete it.
4. **Per-slice gates**: `sbt -batch compile`, targeted specs, a fuzz run
   compared against a measured baseline. Note that the full suite currently
   HANGS on `fsave_frestore_basic` (pre-existing), so use `testOnly`.

## Status

* Slice 1: in flight.
* Slices 2-4: not started.
