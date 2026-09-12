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

## What landed (2026-09-12)

Three commits, in the order this document specified.

**Step 1 -- the decoder owns the EA.** `OpSpec` gained `eaSrcValid` /
`eaSrcShift` / `eaDstValid`, and `OperationDecoder` sets them for every
instruction that has an effective address, including the ones the assembler
builds by hand: `Scc <ea>` (scoped EXACTLY like the assembler's `isSccOp`, so
DBcc's Dn counter and TRAPcc's ttt selector are not mistaken for EAs), `LEA`,
`PEA`, `JMP`, `JSR`, `MOVE from SR/CCR`, `DIV.L`/`MUL.L`.

One substantive amendment to the plan above: the EA is NOT typed by setting
`srcA/srcB/dst.kind` to `EASRC`/`EADST`. Those fields say how an operand is
PLUMBED -- they drive `MicroOpAssembler`'s crack selection and the `bad` tree --
so typing Scc's dst as `EADST` would have changed `usesDstEa` gating AND broken
`OperationDecoderSpec`'s pinned 65536-opword invariant that `dst.kind === EADST`
implies a line nibble in {1,2,3}. The new fields describe only what the EA
MACHINERY must resolve, and leave the data flow exactly where it was. That
preserves this document's point -- declaring "my destination is this EA" does not
require the decoder to own the rest of the instruction -- with a field that
cannot collide with the plumbing.

`eaSrcValid` is DERIVED from the operand kinds each arm already sets, OR'd with
an explicit marker for the hand-built families, so an arm that names `easrc` is
typed by construction. The point was to delete a list, not relocate it.

**Step 2 -- the gate is generic.** Both entry gates are now two terms:

```scala
(spec.eaSrcValid && (srcKlass === MEMINDIRECT)) || (spec.eaDstValid && (dstKlass === MEMINDIRECT))
```

13 predicates deleted from the slot-0 copy, 14 from the slot-1 copy. The
`eaSrcShift` residue survives exactly as predicted, for exactly the predicted
reason.

**The fail-safe is what makes it safe.** The engine's entry SELECTION
(`ucIsMemInd`/`ucMiEntry`) is still per-op and must be -- the entry ROW is the
family-specific part. What changed is its tail: an op no entry classifier claims
used to fall through to `ucEntrySpec.ucEntry`, which for a NON-microcoded op is
ROM row 0, the BCD entry. It now falls to `MI_UNSUPPORTED_ENTRY`, a clean
vector-4 ILLEGAL (the same context-free row `BF_DYN_ILLEGAL` already uses). This
document's "the correct INTERIM behaviour is a clean trap" is now the mechanism
that lets the front gate be generic at all.

**Step 3 -- pinned.** `memind_unrouted_families_trap.s` asserts the trap for both
families, with D0 poisoned as a negative control (the EA field is mode 110 /
reg 000, so D0 is precisely where the old wrong-register write landed) and the
pointer slot and target word checked untouched. FAILS on master (sentinel
0xDEADBEEF), PASSES after.

## Evidence

Fuzz, 200 seeds (`FUZZ_SEED_START=0 FUZZ_SEED_COUNT=200 FUZZ_MINIMIZE=0`),
measured on master immediately before the change and on the change:

| | baseline | after |
|---|---|---|
| divergences | 3 (seeds 80, 109, 127) | 3 (seeds 80, 109, 127) |
| seed 80 | `reg D5: dut=0x00000000 oracle=0x0000007e` | `pc: dut=0x76ab2465`, A7 -8 |
| seed 109 | `pc: dut=0x7bca4112` | `pc: dut=0x7bca4112` |
| seed 127 | `pc: dut=0x3d973c90` | `pc: dut=0x3d973c90` |

No new divergences, and none of the three is fixed -- correctly, because none has
a microcode entry yet. What changed is seed 80's SHAPE: `scs
([0x11,%a5],%d1.l*4,0x0)` stopped writing register D5 and started taking an
exception (A7 drops by exactly 8, an exception frame; the harness installs no
vector-4 handler, so the trap free-runs and shows up as a PC divergence).

Seeds 109 and 127 are byte-identical before and after, and that is the finding
below.

## Corrections to this document

* **"the line-E memory shift ... executes with a garbage address" is WRONG.** It
  already takes a clean vector-4 ILLEGAL, from `MicroOpAssembler`'s `bad`
  (`usesSrcEa && !srcEaOk`: a MEMINDIRECT EA is neither register, nor immediate,
  nor MEMSIMPLE). Fuzz seeds 109/127 (`rol.w`/`lsr.w ([...])`) show A7 dropping
  by exactly 8 -- a frame push -- and their PCs are unchanged by this work.
  The "garbage address" claim holds only for families whose `bad` is FORCED FALSE
  by one of the 28 `!isXxxOp` guards, i.e. precisely the hand-built ones. `Scc`
  was the real instance.
* So the two named-missing families were never symmetric. Only one of them was
  silently wrong; the other was already trapping, just from the wrong place.
* The list was also undercounted: `MOVE from SR/CCR`, `MOVE to SR/CCR`,
  `DIV.L`/`MUL.L` and `CMP2/CHK2` also take an op[5:0] EA and were likewise
  absent. They now route generically. For those, the observable behaviour does
  not change (vector 4 before, vector 4 after) -- only its source does.
* A hole this document did not anticipate: the three REGISTER-BASE bit-field
  entry arms (`ucIsBfDynRd`, `ucIsBfRmwDyn`, `ucIsBfRmw`) read `ucBfEaDec.disp`
  as a complete byte address, but for a MEMINDIRECT EA that field is `bd` -- the
  same silent-wrong class `ucIsBfMemindRd/Rmw` were added to close. The read-only
  arm was protected only by the OLD front gate's narrowness, so making the gate
  generic would have exposed it; the RMW arms were never protected at all,
  because `spec.microcoded` admits them regardless of any gate. All three now
  exclude a memory-indirect EA and fall to the fail-safe.

## Status

Implemented. Follow-ups:

* `Scc <ea>` and the line-E memory shift still need real microcode entries. They
  trap cleanly until then, and `memind_unrouted_families_trap.s` will start
  failing the day one of them is implemented -- which is the right alarm.
* `MOVEM`/`MOVEP` with a memory-indirect EA remain unrouted and deliberately
  untyped: their micro-sequencer entry is NOT mutually exclusive with the
  engine's (`movemBegin` and `ucBegin` gate on each other's ACTIVE/PEND flags,
  not on each other's BEGIN), so admitting them needs a begin-guard change first.
