# The memory-indirect routing gate is keyed on the wrong thing

## The defect is structural, not a missing entry

`DecodeStage` decides whether an instruction must go to microcode by asking
*"which FAMILY is this?"* against a hand-written list:

```scala
val slot1IsMemIndEarly = ... (
  (s1mi_isMove     && srcEa.klass === EaClass.MEMINDIRECT) ||
  (s1mi_isMove     && dstEa.klass === EaClass.MEMINDIRECT) ||
  (s1mi_isAlu      && ...) || (s1mi_isAluDst  && ...) ||
  (s1mi_isAddqSubq && ...) || (s1mi_isImm     && ...) ||
  (s1mi_isDynBitOp && ...) || (s1mi_isSingle  && ...) ||
  (s1mi_isLea      && ...) || (s1mi_isPea     && ...) ||
  (s1mi_isJmp      && ...) || (s1mi_isJsr     && ...))
```

The right question is *"does this instruction's EA need a pointer chain
walked?"* -- a property of the **EA**, which is already decoded and already
carries `klass === EaClass.MEMINDIRECT`. Keying on the family means every new
family must be remembered here, and the file records what happens when it is
not:

> Historically each gate carried its OWN inline copy of the family list, and the
> copies DRIFTED. Every drift has the same failure mode: the family silently
> falls through to the ordinary non-microcoded fast path, whose EA machinery
> cannot walk a pointer chain, so it computes a GARBAGE address -> access fault
> -> wild PC (or, for Scc, a wrong-register write). That has now happened FOUR
> times:
>   task #144/#145  MOVE / ADDA-SUBA-CMPA src
>   task #150       ALU Dn,<ea> RMW dst (opmode 4/5/6) + ADDQ/SUBQ
>   task #152       static bit-op (tt vs ss field collision)
>   2026-09-03      TAS  <- fuzz clusters B/D

Four silent-wrong-answer bugs, each found by fuzzing rather than by
construction. The 2026-09-03 remedy centralised the list so all three gates call
one copy -- a real improvement -- but the list is still PER-FAMILY and still
**duplicated per slot**: 12 predicates for slot 1 (`s1mi_isAlu`, `s1mi_isAluDst`,
`s1mi_isAluLine`, `s1mi_isImm`, ...) against 11 differently-named ones for
slot 0 (`s0IsAluSrcLine`, `s0IsLineImm`, `s0IsFpGenMemEa`, ...). Two lists that
must agree by hand, forever.

## Known missing today

`DecodeStage`'s own note names them:

* **`Scc <ea>`** -- and `sccMemBad`, which would have illegalised it, is DEAD:
  `bad`'s leading `!isSccOp` guard forces `bad` false for every Scc, so the
  `|| sccMemBad` term in the trailing disjunction is unreachable.
* **line-E memory shift/rotate**.

Both were deferred for a real reason: neither fits the `MI_RMW_ENTRY` shape
without new machinery (`Microcode.scala` hardcodes `u.shiftOp`/`u.shiftDir`, so
a memory shift cannot carry its tt/dr through ctx; Scc needs a CONDITION
evaluation in the branch EU rather than an ALU host-op). That justifies the
missing microcode. It does NOT justify the fast path executing them with a
garbage address in the meantime.

## Why the obvious patch is the wrong move

Adding `Scc` to the enumeration makes the symptom go away and leaves the
mechanism that produced four previous bugs fully intact -- the fifth family
drifts out the same way.

Enabling `sccMemBad` instead is worse: it fires for `mode != 0 && mode != 1`,
i.e. EVERY memory mode, while memory-form Scc is implemented and covered by six
corpus tests (`scc_abs_long`, `scc_d16_an_disp`, `scc_mem_an_indirect`,
`scc_mem_byte`, `scc_mem_forms`, `scc_mem_incdec`, including `(d16,An)`, `abs.L`
and indexed operands). That would trap working instructions.

## The generalization

Route on the EA, not the opcode:

> any uop whose decoded EA class is `MEMINDIRECT` goes to microcode,
> regardless of family.

This needs one thing the family list currently provides implicitly: a reliable
"does this instruction actually USE this EA" signal, so an instruction with no
EA is not judged on a don't-care `klass`. `OperationDecoder` already produces
that (`spec.src.kind === OperandKind.EASRC`, and the `usesSrcEa`/`usesDstEa`
predicates `MicroOpAssembler` already computes for `bad`). Keyed that way the
gate needs no family entry, cannot drift between slots, and covers `Scc` and the
line-E memory shift for free -- they would trap or route correctly without being
named.

Until the microcode entries exist, the correct INTERIM behaviour for a family
that cannot be routed is a clean trap, not execution with a garbage address:
a wrong-register write or a wild PC is strictly worse to debug than vector 4.

## Why the generic rule cannot simply replace the list YET

The obvious refactor -- drop the 12 family predicates for

```scala
(usesSrcEa && srcEa.klass === MEMINDIRECT) || (usesDstEa && dstEa.klass === MEMINDIRECT)
```

using the `usesSrcEa`/`usesDstEa` signals `MicroOpAssembler` already computes
from `spec.srcA/srcB/dst.kind` -- would SILENTLY MISS several families, because
`OperationDecoder` deliberately does not type their EAs at all:

* **Scc/DBcc/TRAPcc (line 5, ss==11).** The decoder's own comment: *"the
  assembler builds the WHOLE Scc instruction by hand (branch-EU condition
  write; illegal-gating is via the assembler's isSccOp exclusion, not
  spec.illegal), so this decoder does NOT otherwise touch Scc/DBcc/TRAPcc
  (ss==3) at all"* and *"OpSpec here leaves them illegal; the assembler
  overrides."*
* **LEA / PEA / JMP / JSR.** Matched by OPWORD PATTERN in the assembler
  (`isLeaOp`, `isPeaOp`, `isJmpOp`, `isJsrOp`); there is no `DecOp` for them, so
  `spec.srcA.kind` is not `EASRC`.
* **The line-0 immediate family**, whose EA extension words are SHIFTED, so its
  EA must be decoded from a different word vector (`s1mi_immEa`) rather than
  read from `specs(N).srcEa`.

So the family list is a SYMPTOM. The root cause is that the spec does not
describe the EA for instructions the assembler hand-builds -- and a gate keyed
on the spec therefore cannot see them. Swapping the predicate without fixing
that trades a drift bug for a silent-miss bug, and no existing test would catch
it (the corpus does not currently exercise a memory-INDIRECT Scc at all).

### The ordering this implies

1. **Make the decoder own the EA.** Every instruction that HAS an effective
   address gets it typed in `OpSpec` (`EASRC`/`EADST`), including the ones the
   assembler otherwise builds by hand. Typing the EA does not require the
   decoder to own the rest of the instruction -- Scc can keep its branch-EU
   condition path and still declare "my destination is this EA".
2. **Then the gate becomes generic** -- two terms keyed on EA class, no family
   names -- and `Scc` plus the line-E memory shift are covered without being
   mentioned. The shifted-extension immediate family is the one legitimately
   per-op residue, and it is per-op because its EXTENSION WORDS differ, not
   because its opcode does.

That is the same principle the owner stated for the EA work generally: EAs are
the domain of the EA machinery, and only the strictly necessary should be
per-op typed.

## Status

Not implemented. This touches the decode fast path, which everything depends on,
and the change is a design change rather than a patch -- it wants its own slice
with the fuzz harness run against it, since fuzzing is what caught all four
previous instances.
