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

## Status

Not implemented. This touches the decode fast path, which everything depends on,
and the change is a design change rather than a patch -- it wants its own slice
with the fuzz harness run against it, since fuzzing is what caught all four
previous instances.
